package com.pianostream.core.youtube.api

/**
 * Every `:core:youtube` failure (Interface Freeze — Phase 1b maps all failure
 * paths to one of these; a stale/expired URL is never returned).
 *
 * Messages MUST be URL-redacted (CC4/F13): sites embed `Redact.url()` output or
 * parameter names only — never raw `pot=`/`sig=`/`n=` values.
 */
public sealed class ExtractionException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The player response carried no usable audio format. */
    public class NoFormats(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)

    /** Every catalog client failed; nothing playable exists to return. */
    public class AllClientsFailed(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)

    /** All fallback clients require a poToken we could not mint. */
    public class PoTokenRequired(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)

    /** HTTP 429 (or equivalent throttling) — caller must back off, not retry-loop. */
    public class RateLimited(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)

    /** sig/n solving failed or blew the ~250 ms / ~16 MB QuickJS bounds (F14). */
    public class CipherFailure(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)

    /** Transport-level failure (DNS/TLS/timeout/IO). */
    public class Network(message: String? = null, cause: Throwable? = null) :
        ExtractionException(message, cause)
}
