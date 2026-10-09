package com.pianostream.core.media.lyrics

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pure-JVM cache coverage over a temp dir: memory LRU + disk mirror. */
class LyricsCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache(dir: File = tmp.root, max: Int = 64) =
        LyricsCache(diskDir = dir, maxEntries = max)

    @Test
    fun miss_returns_null() = runTest {
        assertThat(cache().get("nope")).isNull()
    }

    @Test
    fun put_then_get_roundtrips() = runTest {
        val c = cache()
        c.put("m1", "[00:10.00]line")
        assertThat(c.get("m1")).isEqualTo("[00:10.00]line")
    }

    @Test
    fun put_writes_sha256_named_disk_file() = runTest {
        val c = cache()
        c.put("media-42", "raw text")
        val files = tmp.root.listFiles().orEmpty()
        assertThat(files).hasLength(1)
        assertThat(files[0].name).matches("[0-9a-f]{64}\\.lrc")
        assertThat(files[0].readText()).isEqualTo("raw text")
    }

    @Test
    fun disk_hit_promotes_to_memory() = runTest {
        val dir = tmp.newFolder()
        cache(dir).put("m2", "disk raw")
        // A fresh instance shares the disk dir but not memory.
        assertThat(cache(dir).get("m2")).isEqualTo("disk raw")
    }

    @Test
    fun memory_lru_evicts_eldest() = runTest {
        val dir = tmp.newFolder()
        val c = cache(dir, max = 2)
        c.put("a", "1"); c.put("b", "2")
        c.get("a") // touch: b is now eldest
        c.put("c", "3") // evicts b from memory
        // With b's disk mirror gone, a memory hit is the only way it reads.
        assertThat(c.fileFor("b").delete()).isTrue()
        assertThat(c.get("b")).isNull()
        assertThat(c.get("a")).isEqualTo("1")
        assertThat(c.get("c")).isEqualTo("3")
    }
}
