package com.pianostream.core.youtube.cipher

/**
 * One entry of the player-config registry, keyed by player js hash. Values are
 * *function names* inside the EJS-preprocessed player code — never raw JS —
 * enforced by [RemoteConfigValidator] (`^[A-Za-z0-9_$]{1,64}$`).
 */
internal data class PlayerConfig(
    val playerHash: String,
    /** Name of the signature-decipher function. */
    val sig: String,
    /** Name of the `n`-throttling transform function. */
    val nClass: String,
    /** `signatureTimestamp` to send with the player request. */
    val sts: String? = null,
    /** Function-name aliases the preprocessed code may expose. */
    val aliases: Map<String, String> = emptyMap(),
    /** Optional override URL for the preprocessed player js. */
    val playerJsUrl: String? = null,
)

internal data class SolveRequest(
    /** EJS-preprocessed player js source already fetched/seeded. */
    val playerJs: String,
    val config: PlayerConfig,
    /** `signatureCipher` input string from a format entry, if it has one. */
    val signatureCipher: String?,
    /** `n` param value needing transformation, if present. */
    val nCipherInput: String?,
)

internal data class SolvedCipher(
    val signature: String?,
    val n: String?,
)
