package com.pianostream.core.youtube.cipher

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Byte-for-byte cipher contract test (no network): the checked-in
 * `player_v1.js` + `player_configs_v1.json` produce the expected `sig` and `n`
 * values through the real solve pipeline.
 *
 * `FixtureJsEngine` evaluates the fixture's two functions with their exact JS
 * semantics in Kotlin (reverse; x→y) — the QuickJS engine itself is covered
 * on-device in Phase 5a; what this pins is the plumbing: script load, function
 * dispatch by registry names, input marshalling, output capture.
 */
class CipherFixtureTest {

    /** Evaluates the two fixture functions with their JS semantics. */
    private class FixtureJsEngine : JsEngine {
        var loadedScript: String? = null
        val calls = mutableListOf<String>()
        override fun setMemoryLimit(bytes: Long) {}
        override fun setMaxStackSize(bytes: Long) {}
        override fun setEvaluationTimeoutMillis(ms: Long) {}
        override fun interruptEvaluation() {}
        override suspend fun evaluate(code: String): Any? {
            return when {
                code.startsWith("pianostreamSig(") -> {
                    calls += code
                    val arg = code.removePrefix("pianostreamSig(")
                        .removeSuffix(")").trim('"')
                    arg.reversed() // exact fixture semantics
                }
                code.startsWith("pianostreamN(") -> {
                    calls += code
                    val arg = code.removePrefix("pianostreamN(")
                        .removeSuffix(")").trim('"')
                    arg.replace("x", "y") // exact fixture semantics
                }
                else -> {
                    loadedScript = code
                    null
                }
            }
        }
        override fun memoryUsageBytes(): Long = 0
        override fun close() {}
    }

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("player/$name")!!.readBytes()

    @Test
    fun `checked-in player_v1 plus config produce byte-for-byte sig and n`() = runTest {
        val js = fixture("player_v1.js").decodeToString()
        val registry = RemoteConfigValidator.validate(fixture("player_configs_v1.json"))
        assertThat(registry).isNotNull()
        val config = registry!!.configs["v1"]!!

        val engine = FixtureJsEngine()
        val solver = QuickJsCipherSolver(
            solverDispatcher = Dispatchers.Default,
            engineFactory = { engine },
        )
        val solved = solver.solve(
            SolveRequest(
                playerJs = js,
                config = config,
                signatureCipher = "ciphersig",
                nCipherInput = "axaxax",
            )
        )

        assertThat(engine.loadedScript).isEqualTo(js) // script was loaded
        assertThat(solved.signature).isEqualTo("gisrehpic") // "ciphersig".reversed()
        assertThat(solved.n).isEqualTo("ayayay")
    }
}
