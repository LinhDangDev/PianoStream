package com.pianostream.core.data.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.pianostream.core.data.library.db.LibraryDatabase
import com.pianostream.core.data.library.db.entity.DiscEntity
import com.pianostream.core.data.library.db.entity.TrackEntity
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import com.pianostream.core.data.library.scan.LibraryScanDriver
import com.pianostream.core.data.settings.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `library.db` owner — entity→model mapping, FTS search, scan delegation to
 * the injected `LibraryScanDriver` (implemented in `core:local`), and the
 * library half of `deleteAllLocalData()` (CC10). `libraryRootTreeUri` is
 * never cleared by [clearAll] — the SAF grant survives a library wipe
 * (F8/F19); [revokeLibraryAccess] releases it explicitly.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LibraryDatabase,
    private val settings: SettingsDataStore,
    private val scanDriver: LibraryScanDriver,
) : LibraryRepository {

    private val _scanProgress = MutableStateFlow(ScanProgress(0, null))

    override val discs: Flow<List<Disc>> =
        db.discDao().observeAll().map { list -> list.map { it.toModel() } }

    override fun observeTracks(discId: Long): Flow<List<Track>> =
        db.trackDao().observeByDisc(discId)
            .map { list -> list.map { it.toModel() } }

    override suspend fun discById(id: Long): Disc? = db.discDao().byId(id)?.toModel()

    override suspend fun tracksOf(discId: Long): List<Track> =
        db.trackDao().byDisc(discId).map { it.toModel() }

    override fun search(query: String): Flow<List<SearchHit>> = flow {
        emit(
            db.searchDao().search(query, db.trackDao()).map {
                SearchHit(it.trackId, it.discId, it.title, it.snippet)
            },
        )
    }

    override suspend fun distinctComposers(): List<String> =
        db.trackDao().distinctComposers()

    override val scanProgress: Flow<ScanProgress> = _scanProgress

    override suspend fun startScan(): ScanResult {
        val treeUri = settings.libraryRootTreeUri.first() ?: return ScanResult(
            0, 0, 0, 0, warnings = listOf("no library root picked"),
        )
        val result = scanDriver.run(treeUri) { _scanProgress.value = it }
        _scanProgress.value = ScanProgress(0, null)
        if (result.discCount > 0 || result.trackCount > 0) {
            settings.setLibraryLastScanAt(System.currentTimeMillis())
        }
        return result
    }

    override suspend fun revokeLibraryAccess() {
        val treeUri = settings.libraryRootTreeUri.first() ?: return
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(treeUri),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        settings.setLibraryRootTreeUri(null)
    }

    override suspend fun clearAll() {
        db.withTransaction {
            db.trackDao().deleteAll()
            db.cueSheetRawDao().deleteAll()
            db.discDao().deleteAll()
        }
    }

    private fun DiscEntity.toModel() = Disc(
        id = id, docId = docId, treeUri = treeUri, folderPath = folderPath,
        title = title, performer = performer, composer = composer,
        genre = genre, date = date, discId = discId, discNumber = discNumber,
        totalDiscs = totalDiscs, cueUri = cueUri, flacUri = flacUri,
        logUri = logUri, artworkUri = artworkUri, albumGainDb = albumGainDb,
        albumPeak = albumPeak, flacDurationMs = flacDurationMs,
        flacSizeBytes = flacSizeBytes, flacLastModified = flacLastModified,
        scannedAt = scannedAt,
    )

    private fun TrackEntity.toModel() = Track(
        id = id, discId = discId, trackNo = trackNo, title = title,
        performer = performer, composer = composer, startMs = startMs,
        endMs = endMs, pregapStartMs = pregapStartMs, gainDb = gainDb,
        peak = peak, isrc = isrc, flags = flags,
    )
}
