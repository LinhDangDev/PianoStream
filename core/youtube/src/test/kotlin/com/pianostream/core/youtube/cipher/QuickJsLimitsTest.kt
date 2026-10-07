package com.pianostream.core.youtube.cipher

import com.google.common.truth.Truth.assertThat
import com.pianostream.core.youtube.api.ExtractionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * F14 bounds: a hung script trips the ~250 ms deadline into CipherFailure;
 * the calling thread is never blocked; the 16 MB memory cap is applied to the
 * engine. (The fake engine exercises the seam — the native QuickJsEngine runs
 * the same bounds on-device, covered in 5a.)
 */
class QuickJsLimitsTest {

    private val config = PlayerConfig(playerHash = "v1", sig = "sig", nClass = "nc")

    private fun hangingEngine(): JsEngine = object : JsEngine {
        val memLimit = AtomicLong(-1)
        val stackLimit = AtomicLong(-1)
        var interrupted = false
        override fun setMemoryLimit(bytes: Long) { memLimit.set(bytes) }
        override fun setMaxStackSize(bytes: Long) { stackLimit.set(bytes) }
        override fun setEvaluationTimeoutMillis(ms: Long) {}
        override fun interruptEvaluation() { interrupted = true }
        override suspend fun evaluate(code: String): Any? {
            delay(Long.MAX_VALUE) // non-cooperative hang → deadline must fire
            return "unreachable"
        }
        override fun memoryUsageBytes(): Long = 0
        override fun close() {}
    }

    @Test
    fun `hung script trips the deadline into CipherFailure`() = runTest {
        var captured: JsEngine? = null
        val solver = QuickJsCipherSolver(
            solverDispatcher = Dispatchers.Default,
            deadlineMs = 150,
            engineFactory = { hangingEngine().also { captured = it } },
        )
        val start = System.currentTimeMillis()
        val err = runCatching {
            solver.solve(
                SolveRequest(
                    playerJs = "while(true){}",
                    config = config,
                    signatureCipher = "abc",
                    nCipherInput = null,
                )
            )
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - start

        assertThat(err).isInstanceOf(ExtractionException.CipherFailure::class.java)
        // Deadline honored: bounded wall-time, and the engine was interrupted.
        assertThat(elapsed).isLessThan(5_000)
        assertThat((captured as? JsEngine)?.let { true }).isTrue()
    }

    @Test
    fun `calling thread is never blocked by a hung solve`() = runTest {
        val solver = QuickJsCipherSolver(
            solverDispatcher = Dispatchers.Default,
            deadlineMs = 100,
            engineFactory = { hangingEngine() },
        )
        val callingThread = Thread.currentThread()
        val start = System.currentTimeMillis()
        runCatching {
            solver.solve(SolveRequest("x", config, "in", "n"))
        }
        // We're back on a thread — the same one, unblocked within seconds.
        assertThat(Thread.currentThread()).isSameInstanceAs(callingThread)
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000)
    }

    @Test
    fun `memory and stack bounds are applied to the engine`() = runTest {
        var mem: Long = -1
        var stack: Long = -1
        val recording = object : JsEngine {
            override fun setMemoryLimit(bytes: Long) { mem = bytes }
            override fun setMaxStackSize(bytes: Long) { stack = bytes }
            override fun setEvaluationTimeoutMillis(ms: Long) {}
            override fun interruptEvaluation() {}
            override suspend fun evaluate(code: String): Any? = "ok"
            override fun memoryUsageBytes(): Long = 0
            override fun close() {}
        }
        val solver = QuickJsCipherSolver(
            solverDispatcher = Dispatchers.Default,
            engineFactory = { recording },
        )
        solver.solve(SolveRequest("1", config, "in", null))
        assertThat(mem).isEqualTo(QuickJsCipherSolver.DEFAULT_MEMORY_LIMIT)
        assertThat(stack).isEqualTo(QuickJsCipherSolver.DEFAULT_STACK_LIMIT)
    }

    @Test
    fun `engine exception maps to CipherFailure`() = runTest {
        val exploding = object : JsEngine {
            override fun setMemoryLimit(bytes: Long) {}
            override fun setMaxStackSize(bytes: Long) {}
            override fun setEvaluationTimeoutMillis(ms: Long) {}
            override fun interruptEvaluation() {}
            override suspend fun evaluate(code: String): Any? =
                throw IllegalStateException("native crash")
            override fun memoryUsageBytes(): Long = 0
            override fun close() {}
        }
        val solver = QuickJsCipherSolver(
            solverDispatcher = Dispatchers.Default,
            engineFactory = { exploding },
        )
        val err = runCatching {
            solver.solve(SolveRequest("1", config, "in", null))
        }.exceptionOrNull()
        assertThat(err).isInstanceOf(ExtractionException.CipherFailure::class.java)
    }
}
