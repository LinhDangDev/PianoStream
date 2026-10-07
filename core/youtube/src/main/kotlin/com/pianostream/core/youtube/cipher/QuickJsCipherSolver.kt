package com.pianostream.core.youtube.cipher

import com.pianostream.core.youtube.api.ExtractionException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Primary [CipherSolver]: bounded QuickJS eval of the EJS-preprocessed player
 * code on a dedicated single-thread dispatcher.
 *
 * F14 enforcement:
 *  - ~16 MB engine memory cap + bounded stack via [JsEngine] setters;
 *  - ~250 ms hard deadline — the engine's own `evaluationTimeoutMillis` plus
 *    an outer `withTimeoutOrNull` that calls `interruptEvaluation()`;
 *  - every eval runs on [solverDispatcher] so a hung script can never block
 *    the calling (loading) thread;
 *  - `catch (Throwable)` at the boundary (M7) — a native/engine crash is a
 *    [ExtractionException.CipherFailure], never propagated.
 */
internal class QuickJsCipherSolver(
    private val solverDispatcher: CoroutineDispatcher,
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT,
    private val stackLimitBytes: Long = DEFAULT_STACK_LIMIT,
    private val engineFactory: (CoroutineDispatcher) -> JsEngine =
        { QuickJsEngine(it) },
) : CipherSolver {

    override suspend fun solve(request: SolveRequest): SolvedCipher {
        return runCatching { solveInner(request) }
            .getOrElse { e ->
                if (e is ExtractionException) throw e
                throw ExtractionException.CipherFailure(cause = e)
            }
    }

    private suspend fun solveInner(request: SolveRequest): SolvedCipher =
        withContext(solverDispatcher) {
            val engine = try {
                engineFactory(solverDispatcher).also {
                    it.setMemoryLimit(memoryLimitBytes)
                    it.setMaxStackSize(stackLimitBytes)
                    it.setEvaluationTimeoutMillis(deadlineMs)
                }
            } catch (t: Throwable) {
                throw ExtractionException.CipherFailure("engine init failed", t)
            }

            try {
                // Load the preprocessed player code.
                evalBounded(engine, request.playerJs)

                val signature = request.signatureCipher?.let { input ->
                    callString(engine, request.config.sig, input)
                }
                val n = request.nCipherInput?.let { input ->
                    callString(engine, request.config.nClass, input)
                }
                SolvedCipher(signature = signature, n = n)
            } catch (t: Throwable) {
                // M7: nothing escapes the solver boundary.
                if (t is ExtractionException) throw t
                throw ExtractionException.CipherFailure(cause = t)
            } finally {
                runCatching { engine.close() }
            }
        }

    /** Eval with the F14 hard deadline; timeout interrupts the engine and
     *  raises [ExtractionException.CipherFailure]. Returns the eval value. */
    private suspend fun evalBounded(engine: JsEngine, code: String): Any? {
        val result = withTimeoutOrNull(deadlineMs) {
            runCatching { engine.evaluate(code) }
        }
        if (result == null) {
            runCatching { engine.interruptEvaluation() }
            throw ExtractionException.CipherFailure("eval exceeded ${deadlineMs}ms")
        }
        return result.getOrElse {
            if (it is ExtractionException) throw it
            throw ExtractionException.CipherFailure(cause = it)
        }
    }

    private suspend fun callString(engine: JsEngine, fn: String, input: String): String {
        val arg = '"' + input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + '"'
        val out = evalBounded(engine, "$fn($arg)")
        return out?.toString()
            ?: throw ExtractionException.CipherFailure("$fn returned null")
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 250L
        const val DEFAULT_MEMORY_LIMIT = 16L * 1024 * 1024
        const val DEFAULT_STACK_LIMIT = 512L * 1024
    }
}
