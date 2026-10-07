package com.pianostream.core.data.util

/**
 * URL redaction helper (Interface Freeze — CC4, SEC-F13).
 *
 * Exactly one helper strips the sensitive query parameters
 * `pot|sig|n|lsig|expire|ip|cpn|range` before any `googlevideo`/`youtubei` URL
 * is logged or embedded in an error message. Values are masked in place as
 * `key=[REDACTED]` so the URL's structure stays readable for debugging.
 * Request headers are logged by name only. All logging that uses this helper
 * is `DEBUG`-only. `docs/extraction-failure-runbook.md` starts with
 * "redact URLs before posting".
 */
public object Redact {

    /** Query parameter names whose values are never logged. */
    private val STRIPPED_PARAMS: Set<String> = setOf(
        "pot", "sig", "n", "lsig", "expire", "ip", "cpn", "range",
    )

    private const val MASK: String = "[REDACTED]"

    /**
     * Returns `raw` with every stripped-parameter value replaced by
     * `[REDACTED]`. Non-URL input (no `?` query) passes through unchanged
     * unless it happens to contain a `k=v` pair for a stripped name after a
     * `?` or `&`.
     */
    public fun url(raw: String): String {
        if (raw.isEmpty()) return raw
        val qStart = raw.indexOf('?')
        if (qStart < 0 || qStart == raw.length - 1) return raw

        val head = raw.substring(0, qStart + 1)
        val tail = raw.substring(qStart + 1)
        val fragStart = tail.indexOf('#')
        val query = if (fragStart >= 0) tail.substring(0, fragStart) else tail
        val fragment = if (fragStart >= 0) tail.substring(fragStart) else ""

        val redacted = query.split('&').joinToString("&") { param ->
            val key = param.substringBefore('=').lowercase()
            if (key in STRIPPED_PARAMS && param.contains('=')) {
                param.substringBefore('=') + "=" + MASK
            } else {
                param
            }
        }
        return head + redacted + fragment
    }
}
