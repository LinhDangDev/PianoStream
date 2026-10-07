package com.pianostream.core.youtube.cipher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Schema validator for registry payloads (F2/U3 — fail closed).
 *
 *  - payload size cap [MAX_BYTES] enforced by the caller before this runs;
 *  - top-level allow-list: `version`, `updatedAt`, `configs`, `sig`;
 *    unknown keys are ignored, never fatal;
 *  - `configs` maps playerHash → {`sig`,`nClass`,`sts`,`aliases`,`playerJs`};
 *    identifier fields must match `^[A-Za-z0-9_$]{1,64}$`;
 *  - an optional top-level `sig` is *honored* (stored for a future
 *    verification hook) but not required — community mirrors ship unsigned
 *    payloads (residual risk recorded in the plan risk register);
 *  - anything malformed → reject; the caller must never fall back to an
 *    unverified cached config.
 */
internal object RemoteConfigValidator {

    const val MAX_BYTES = 256 * 1024

    private val IDENT = Regex("^[A-Za-z0-9_$]{1,64}$")
    private val HASH = Regex("^[A-Za-z0-9._~-]{1,128}$")
    private val TOP_KEYS = setOf("version", "updatedAt", "configs", "sig")
    private val CONFIG_KEYS = setOf("sig", "nClass", "sts", "aliases", "playerJs")

    private val json = Json { ignoreUnknownKeys = true }

    data class Registry(
        val configs: Map<String, PlayerConfig>,
        /** Optional upstream signature — recorded, not yet verified. */
        val sig: String? = null,
    )

    /** Returns the parsed registry or null when invalid (fail closed). */
    fun validate(payload: ByteArray): Registry? {
        if (payload.size > MAX_BYTES) return null
        val root = runCatching {
            json.parseToJsonElement(payload.decodeToString()).jsonObject
        }.getOrNull() ?: return null

        val configsObj = root["configs"] as? JsonObject ?: return null
        val out = mutableMapOf<String, PlayerConfig>()

        for ((hash, rawCfg) in configsObj) {
            if (!HASH.matches(hash)) return null
            val cfg = rawCfg as? JsonObject ?: return null
            val sig = cfg["sig"]?.jsonPrimitive?.contentOrNull
            val nClass = cfg["nClass"]?.jsonPrimitive?.contentOrNull
            val sts = cfg["sts"]?.jsonPrimitive?.contentOrNull
            val playerJs = cfg["playerJs"]?.jsonPrimitive?.contentOrNull

            if (sig == null || nClass == null) return null
            if (!IDENT.matches(sig) || !IDENT.matches(nClass)) return null
            if (sts != null && !IDENT.matches(sts)) return null
            if (playerJs != null &&
                !(playerJs.startsWith("https://") || playerJs.startsWith("file://"))
            ) return null

            val aliases = mutableMapOf<String, String>()
            (cfg["aliases"] as? JsonObject)?.forEach { (k, v) ->
                val value = v.jsonPrimitive.contentOrNull ?: return null
                if (!IDENT.matches(k) || !IDENT.matches(value)) return null
                aliases[k] = value
            }

            out[hash] = PlayerConfig(
                playerHash = hash,
                sig = sig,
                nClass = nClass,
                sts = sts,
                aliases = aliases,
                playerJsUrl = playerJs,
            )
        }
        if (out.isEmpty()) return null

        return Registry(
            configs = out,
            sig = root["sig"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
