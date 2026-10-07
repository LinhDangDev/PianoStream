package com.pianostream.core.youtube.potoken

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F3/CC10: visitor-bound and video-bound (GVS) tokens live in separate
 * in-memory caches that never cross-use; both are zeroed by the wipe hook.
 */
class PoTokenCacheTest {

    private var now = 0L
    private val cache = PoTokenCache(uptimeMs = { now })

    @Test
    fun `visitor and gvs caches are separate`() {
        cache.put(PoTokenBinding.VISITOR, "k", "visitorTok")
        cache.put(PoTokenBinding.GVS, "k", "gvsTok")
        assertThat(cache.get(PoTokenBinding.VISITOR, "k")).isEqualTo("visitorTok")
        assertThat(cache.get(PoTokenBinding.GVS, "k")).isEqualTo("gvsTok")
        // A GVS key never surfaces in the visitor table.
        assertThat(cache.size(PoTokenBinding.VISITOR)).isEqualTo(1)
        assertThat(cache.size(PoTokenBinding.GVS)).isEqualTo(1)
    }

    @Test
    fun `expiry evicts`() {
        cache.put(PoTokenBinding.VISITOR, "k", "tok")
        now += PoTokenCache.DEFAULT_TTL_MS + 1
        assertThat(cache.get(PoTokenBinding.VISITOR, "k")).isNull()
    }

    @Test
    fun `wipe zeroes both caches`() {
        cache.put(PoTokenBinding.VISITOR, "k", "a")
        cache.put(PoTokenBinding.GVS, "k", "b")
        cache.wipe()
        assertThat(cache.get(PoTokenBinding.VISITOR, "k")).isNull()
        assertThat(cache.get(PoTokenBinding.GVS, "k")).isNull()
        assertThat(cache.size(PoTokenBinding.VISITOR)).isEqualTo(0)
        assertThat(cache.size(PoTokenBinding.GVS)).isEqualTo(0)
    }

    @Test
    fun `provider caches minted tokens per binding`() = runTest {
        val minted = mutableListOf<Pair<PoTokenBinding, String>>()
        val provider = PoTokenProviderImpl(
            minter = object : PoTokenMinter {
                override suspend fun mint(binding: PoTokenBinding, key: String): String? {
                    minted += binding to key
                    return "${binding.name}@$key"
                }
            },
            cache = PoTokenCache(uptimeMs = { now }),
        )
        val visitor = provider.token(PoTokenBinding.VISITOR, "vd1")
        val gvs = provider.token(PoTokenBinding.GVS, "vid1")
        assertThat(visitor).isEqualTo("VISITOR@vd1")
        assertThat(gvs).isEqualTo("GVS@vid1")
        // Second read hits cache — no second mint.
        provider.token(PoTokenBinding.VISITOR, "vd1")
        assertThat(minted.count { it.first == PoTokenBinding.VISITOR }).isEqualTo(1)
        // Wipe forces a re-mint.
        provider.wipe()
        provider.token(PoTokenBinding.VISITOR, "vd1")
        assertThat(minted.count { it.first == PoTokenBinding.VISITOR }).isEqualTo(2)
    }
}
