package com.pianostream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Compile assertion that the Phase-1a BuildConfig pins exist and are readable
 * from :app (F1/F2): `REGISTRY_HOSTS`, `RELEASE_CERT_SHA256`, `UPDATE_PUBKEY`,
 * `UPDATE_REPO`, `UPDATE_ASSET`. `RELEASE_CERT_SHA256` must equal the
 * fingerprint of the keystore generated during scaffold.
 */
class BuildConfigContractTest {

    @Test
    fun `updater and registry pins are readable`() {
        assertThat(BuildConfig.UPDATE_REPO).isEqualTo("LinhDangDev/PianoStream")
        assertThat(BuildConfig.UPDATE_ASSET).isNotEmpty()

        assertThat(BuildConfig.REGISTRY_HOSTS.split(',').map { it.trim() })
            .containsExactly(
                "raw.githubusercontent.com",
                "cdn.jsdelivr.net",
                "github.com",
                "objects.githubusercontent.com",
            )

        // SHA-256 fingerprint = 64 lowercase hex chars, baked at scaffold.
        assertThat(BuildConfig.RELEASE_CERT_SHA256)
            .matches("[0-9a-f]{64}")

        // minisign public key, baked at scaffold.
        assertThat(BuildConfig.UPDATE_PUBKEY).startsWith("RWQ")
    }
}
