package com.pianostream.core.media.lyrics

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Resolution-order coverage with fake seams — no Android, no network. */
class LyricsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val synced = "[00:10.00]local line"
    private val remoteSynced = "[00:20.00]remote line"

    private fun query(uri: String? = "file:///music/song.flac") = LyricsQuery(
        mediaId = "m1", title = "T", artist = "A", durationMs = 60_000L, localFileUri = uri,
    )

    private class FakeLocal(var result: String? = null) : LocalLrcSource {
        var calls = 0
        override suspend fun find(localFileUri: String): String? {
            calls++; return result
        }
    }

    private class FakeRemote(var result: String? = null) : RemoteLrcSource {
        var calls = 0
        override suspend fun fetch(query: LyricsQuery): String? {
            calls++; return result
        }
    }

    private fun repo(
        local: LocalLrcSource,
        remote: RemoteLrcSource,
        dir: File = tmp.root,
        offline: Boolean = false,
    ) = LyricsRepositoryImpl(
        local = local, remote = remote, cache = LyricsCache(dir),
        offlineOnly = { offline }, io = Dispatchers.Unconfined,
    )

    @Test
    fun local_sibling_wins_over_remote() = runTest {
        val local = FakeLocal(synced); val remote = FakeRemote(remoteSynced)
        val lyrics = repo(local, remote, tmp.newFolder()).get(query())
        assertThat(lyrics).isEqualTo(Lyrics.Synced(listOf(LyricsLine(10_000L, "local line"))))
        assertThat(local.calls).isEqualTo(1)
        assertThat(remote.calls).isEqualTo(0)
    }

    @Test
    fun missing_sibling_falls_to_remote() = runTest {
        val local = FakeLocal(null); val remote = FakeRemote(remoteSynced)
        val lyrics = repo(local, remote, tmp.newFolder()).get(query())
        assertThat(lyrics).isEqualTo(Lyrics.Synced(listOf(LyricsLine(20_000L, "remote line"))))
        assertThat(remote.calls).isEqualTo(1)
    }

    @Test
    fun unusable_local_file_does_not_suppress_remote() = runTest {
        val local = FakeLocal("   \n[ti:tags only]"); val remote = FakeRemote(remoteSynced)
        val lyrics = repo(local, remote, tmp.newFolder()).get(query())
        assertThat(lyrics).isInstanceOf(Lyrics.Synced::class.java)
        assertThat(remote.calls).isEqualTo(1)
    }

    @Test
    fun no_local_uri_goes_straight_to_remote() = runTest {
        val local = FakeLocal(synced); val remote = FakeRemote("plain words")
        val lyrics = repo(local, remote, tmp.newFolder()).get(query(uri = null))
        assertThat(lyrics).isEqualTo(Lyrics.Plain("plain words"))
        assertThat(local.calls).isEqualTo(0)
    }

    @Test
    fun offlineOnly_skips_remote() = runTest {
        val local = FakeLocal(null); val remote = FakeRemote(remoteSynced)
        val lyrics = repo(local, remote, tmp.newFolder(), offline = true).get(query())
        assertThat(lyrics).isNull()
        assertThat(remote.calls).isEqualTo(0)
    }

    @Test
    fun cache_hit_short_circuits_everything() = runTest {
        val dir = tmp.newFolder()
        LyricsCache(dir).put("m1", synced)
        val local = FakeLocal(null); val remote = FakeRemote(null)
        val lyrics = repo(local, remote, dir).get(query())
        assertThat(lyrics).isEqualTo(Lyrics.Synced(listOf(LyricsLine(10_000L, "local line"))))
        assertThat(local.calls).isEqualTo(0)
        assertThat(remote.calls).isEqualTo(0)
    }

    @Test
    fun remote_result_is_cached_for_next_get() = runTest {
        val dir = tmp.newFolder()
        val remote = FakeRemote(remoteSynced)
        val r = repo(FakeLocal(null), remote, dir)
        r.get(query(uri = null))
        r.get(query(uri = null))
        assertThat(remote.calls).isEqualTo(1)
    }

    @Test
    fun total_miss_returns_null_and_never_caches() = runTest {
        val dir = tmp.newFolder()
        val remote = FakeRemote(null)
        val r = repo(FakeLocal(null), remote, dir)
        assertThat(r.get(query(uri = null))).isNull()
        assertThat(r.get(query(uri = null))).isNull()
        assertThat(remote.calls).isEqualTo(2) // misses are not cached
    }
}
