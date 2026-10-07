package com.pianostream.core.youtube.cipher

import com.dokar.quickjs.QuickJs

/**
 * The minimal JS-runtime seam [QuickJsCipherSolver] needs. The production impl
 * wraps `quickjs-kt`; unit tests inject fakes (hang/memory-growth/scripted
 * answers) so the F14 deadline, memory bound, and non-blocking-call-site
 * guarantees are exercised on the JVM — the native engine itself is covered
 * on-device in Phase 5a.
 */
internal interface JsEngine : AutoCloseable {
    fun setMemoryLimit(bytes: Long)
    fun setMaxStackSize(bytes: Long)
    fun setEvaluationTimeoutMillis(ms: Long)
    fun interruptEvaluation()
    suspend fun evaluate(code: String): Any?
    fun memoryUsageBytes(): Long
    override fun close()
}

internal class QuickJsEngine(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : JsEngine {
    private val qjs = QuickJs.create(dispatcher)

    override fun setMemoryLimit(bytes: Long) {
        qjs.memoryLimit = bytes
    }
    override fun setMaxStackSize(bytes: Long) {
        qjs.maxStackSize = bytes
    }
    override fun setEvaluationTimeoutMillis(ms: Long) {
        qjs.evaluationTimeoutMillis = ms
    }
    override fun interruptEvaluation() = qjs.interruptEvaluation()
    override suspend fun evaluate(code: String): Any? =
        qjs.evaluate(code, "<player>", false)
    override fun memoryUsageBytes(): Long = qjs.memoryUsage.memoryUsedSize
    override fun close() = qjs.close()
}
