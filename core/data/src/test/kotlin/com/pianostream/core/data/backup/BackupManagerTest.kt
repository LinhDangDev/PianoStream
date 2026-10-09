package com.pianostream.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pianostream.core.data.downloads.DownloadEntity
import com.pianostream.core.data.playback.db.PlaybackDatabase
import com.pianostream.core.data.playback.db.entity.FavoriteEntity
import com.pianostream.core.data.playback.db.entity.PlayHistoryEntity
import com.pianostream.core.data.playback.db.entity.PlaylistEntity
import com.pianostream.core.data.playback.db.entity.PlaylistItemEntity
import com.pianostream.core.data.playback.db.entity.QueueItemEntity
import com.pianostream.core.data.playback.db.entity.QueueStateEntity
import com.pianostream.core.data.settings.SettingsBackupPort
import com.pianostream.core.data.settings.SettingsDataStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `.psb` pack/unpack round-trip (Robolectric — real org.json, real filesDir):
 * every playback.db section serializes into `data.json` and merges back on
 * an empty database, and the extractor refuses hostile entry names.
 */
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: PlaybackDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var manager: BackupManager
    private lateinit var downloadsDir: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PlaybackDatabase::class.java).build()
        settings = SettingsDataStore.default(context)
        settings.clear()
        manager = BackupManager(context, db, SettingsBackupPort(settings))
        downloadsDir = File(context.filesDir, "downloads").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        db.close()
        downloadsDir.deleteRecursively()
    }

    private suspend fun seedDb() {
        db.favoriteDao().upsert(
            FavoriteEntity("pianostream://stream/abc", "Song A", "Artist", null, "YOUTUBE", 11L),
        )
        db.favoriteDao().upsert(
            FavoriteEntity("pianostream://local/x", "Local A", null, null, "LOCAL", 12L, missing = true),
        )
        db.playlistDao().insert(PlaylistEntity(id = 7L, name = "Mix", createdAt = 99L, description = "d"))
        db.playlistItemDao().upsert(
            PlaylistItemEntity(7L, "pianostream://stream/abc", 0, "Song A", "Artist", null, "YOUTUBE", 5L, 1000L),
        )
        db.queueDao().replaceQueue(
            listOf(
                QueueItemEntity(0, 0, "pianostream://stream/abc", "Song A", "Artist", null, "YOUTUBE", 1000L, 3L),
            ),
            QueueStateEntity(1, 0, 42L, 0, shuffle = false, updatedAt = 77L),
        )
        db.playHistoryDao().insertAndPrune(
            // playedAt must sit inside the DAO's keep-window or insertAndPrune
            // deletes it again on restore.
            PlayHistoryEntity(
                0, "pianostream://stream/abc", "Song A", "Artist", null, "YOUTUBE",
                System.currentTimeMillis(), 900L,
            ),
            500,
            0L,
        )
        db.downloadDao().upsert(
            DownloadEntity("abc", "Song A", "Artist", null, null, 5L, 5L, "DONE", null, 9L, 9L),
        )
        File(downloadsDir, "abc.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        settings.setThemeMode(SettingsDataStore.ThemeMode.DARK)
        settings.setPreferredItag(140)
        settings.setMonoAudio(true)
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val buf = ByteArrayOutputStream()
                zip.copyTo(buf)
                out[entry.name] = buf.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `export then restore round-trips every section`() = runBlocking {
        seedDb()
        val buf = ByteArrayOutputStream()
        val report = manager.writeBackup(buf, true, "1.0.0-test", listOf("lofi", "jazz"))

        assertThat(report.playlists).isEqualTo(1)
        assertThat(report.favorites).isEqualTo(2)
        assertThat(report.downloads).isEqualTo(1)
        assertThat(report.downloadFiles).isEqualTo(1)
        assertThat(report.downloadBytes).isEqualTo(5L)

        val entries = zipEntries(buf.toByteArray())
        assertThat(entries.keys).containsExactly("manifest.json", "data.json", "downloads/abc.bin")
        assertThat(entries.getValue("downloads/abc.bin")).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))

        val manifest = JSONObject(String(entries.getValue("manifest.json")))
        assertThat(manifest.getInt("formatVersion")).isEqualTo(1)
        assertThat(manifest.getString("app")).isEqualTo("com.pianostream")
        assertThat(manifest.getString("appVersion")).isEqualTo("1.0.0-test")
        assertThat(manifest.getLong("exportedAt")).isGreaterThan(0L)

        // data.json serialize → parse → equal (the spec's round-trip).
        val data = JSONObject(String(entries.getValue("data.json")))
        assertThat(data.getJSONArray("favorites").length()).isEqualTo(2)
        assertThat(data.getJSONArray("playlists").getJSONObject(0).getInt("id")).isEqualTo(7)
        assertThat(
            data.getJSONArray("playlists").getJSONObject(0)
                .getJSONArray("items").getJSONObject(0).getString("mediaId"),
        ).isEqualTo("pianostream://stream/abc")
        assertThat(data.getJSONArray("queueItems").length()).isEqualTo(1)
        assertThat(data.getJSONObject("queueState").getLong("positionMs")).isEqualTo(42L)
        assertThat(data.getJSONArray("playHistory").length()).isEqualTo(1)
        assertThat(data.getJSONArray("downloads").getJSONObject(0).getBoolean("fileIncluded")).isTrue()
        assertThat(data.getJSONArray("recentSearches").getString(0)).isEqualTo("lofi")
        assertThat(data.getJSONObject("settings").getString("themeMode")).isEqualTo("DARK")
        assertThat(data.getJSONObject("settings").getInt("preferredItag")).isEqualTo(140)

        // Restore into a wiped database + empty downloads dir.
        withContext(Dispatchers.IO) { db.clearAllTables() }
        downloadsDir.deleteRecursively()
        settings.clear()

        val parsed = manager.parseBackup(ByteArrayInputStream(buf.toByteArray()))
        val copied = manager.extractDownloadFiles(ByteArrayInputStream(buf.toByteArray()))
        val counts = manager.applyData(parsed.data)

        assertThat(copied).isEqualTo(1)
        assertThat(File(downloadsDir, "abc.bin").readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(counts.playlists).isEqualTo(1)
        assertThat(counts.favorites).isEqualTo(2)
        assertThat(counts.downloads).isEqualTo(1)
        assertThat(counts.recentSearches).containsExactly("lofi", "jazz").inOrder()

        assertThat(db.favoriteDao().observeFavorites().first().map { it.mediaId })
            .containsExactly("pianostream://stream/abc", "pianostream://local/x")
        assertThat(db.playlistItemDao().snapshot(7L).single().position).isEqualTo(0)
        assertThat(db.queueDao().queueSnapshot().single().mediaId)
            .isEqualTo("pianostream://stream/abc")
        assertThat(db.queueDao().queueState()?.positionMs).isEqualTo(42L)
        assertThat(db.playHistoryDao().observeHistory().first().single().playedMs).isEqualTo(900L)
        // filePath rewritten to this device's restored file.
        assertThat(db.downloadDao().get("abc")!!.filePath)
            .isEqualTo(File(downloadsDir, "abc.bin").absolutePath)
        assertThat(settings.themeMode.first()).isEqualTo(SettingsDataStore.ThemeMode.DARK)
        assertThat(settings.preferredItag.first()).isEqualTo(140)
        assertThat(settings.monoAudio.first()).isTrue()
    }

    @Test
    fun `export without files still lists downloads in data_json`() = runBlocking {
        seedDb()
        val buf = ByteArrayOutputStream()
        manager.writeBackup(buf, false, "1.0.0", emptyList())
        val entries = zipEntries(buf.toByteArray())
        assertThat(entries.keys).containsExactly("manifest.json", "data.json")
        val data = JSONObject(String(entries.getValue("data.json")))
        assertThat(data.getJSONArray("downloads").length()).isEqualTo(1)
        assertThat(
            data.getJSONArray("downloads").getJSONObject(0).getBoolean("fileIncluded"),
        ).isFalse()
    }

    @Test
    fun `extract refuses zip-slip and nested names`() {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            listOf(
                "downloads/../evil.bin",
                "downloads/../../escape.bin",
                "downloads/nested/deep.bin",
                "downloads/",
                "downloads/..bin",
                "evil.bin",
                "downloads/ok.bin",
            ).forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(9))
                zip.closeEntry()
            }
        }

        val copied = manager.extractDownloadFiles(ByteArrayInputStream(buf.toByteArray()))

        assertThat(copied).isEqualTo(1)
        assertThat(File(downloadsDir, "ok.bin").isFile).isTrue()
        assertThat(File(context.filesDir, "evil.bin").exists()).isFalse()
        assertThat(File(context.filesDir.parentFile, "escape.bin").exists()).isFalse()
        assertThat(File(downloadsDir, "nested").exists()).isFalse()
    }

    @Test
    fun `extract skips files already on disk`() = runBlocking {
        seedDb()
        val buf = ByteArrayOutputStream()
        manager.writeBackup(buf, true, "1.0.0", emptyList())
        // File stays put — restore must not overwrite it.
        val copied = manager.extractDownloadFiles(ByteArrayInputStream(buf.toByteArray()))
        assertThat(copied).isEqualTo(0)
        assertThat(File(downloadsDir, "abc.bin").readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `manifest validation rejects foreign formats`() = runBlocking {
        seedDb()
        val buf = ByteArrayOutputStream()
        manager.writeBackup(buf, true, "1.0.0", emptyList())
        val entries = zipEntries(buf.toByteArray())

        // formatVersion != 1 → InvalidBackupException.
        val badVersion = JSONObject(entries.getValue("manifest.json").decodeToString())
            .put("formatVersion", 2)
        val badZip = ByteArrayOutputStream()
        ZipOutputStream(badZip).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(badVersion.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(entries.getValue("data.json"))
            zip.closeEntry()
        }
        val err = runCatching {
            manager.parseBackup(ByteArrayInputStream(badZip.toByteArray()))
        }.exceptionOrNull()
        assertThat(err).isInstanceOf(BackupManager.InvalidBackupException::class.java)

        // No manifest at all → rejected.
        val empty = ByteArrayOutputStream()
        ZipOutputStream(empty).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        assertThat(
            runCatching {
                manager.parseBackup(ByteArrayInputStream(empty.toByteArray()))
            }.exceptionOrNull(),
        ).isInstanceOf(BackupManager.InvalidBackupException::class.java)
    }

    @Test
    fun `restore merges instead of wiping`() = runBlocking {
        seedDb()
        val buf = ByteArrayOutputStream()
        manager.writeBackup(buf, true, "1.0.0", emptyList())

        // A pre-existing local row the backup doesn't carry survives;
        // a same-PK row loses to the incoming one.
        db.favoriteDao().upsert(
            FavoriteEntity("pianostream://stream/keep", "Keep", null, null, "YOUTUBE", 1L),
        )
        db.favoriteDao().upsert(
            FavoriteEntity("pianostream://stream/abc", "Old title", null, null, "YOUTUBE", 2L),
        )

        val parsed = manager.parseBackup(ByteArrayInputStream(buf.toByteArray()))
        manager.applyData(parsed.data)

        val favs = db.favoriteDao().observeFavorites().first().associateBy { it.mediaId }
        assertThat(favs.keys).containsExactly(
            "pianostream://stream/abc", "pianostream://local/x", "pianostream://stream/keep",
        )
        assertThat(favs.getValue("pianostream://stream/abc").title).isEqualTo("Song A")
    }
}
