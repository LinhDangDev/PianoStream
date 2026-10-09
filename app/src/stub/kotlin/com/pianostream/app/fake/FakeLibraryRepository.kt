package com.pianostream.app.fake

import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Deterministic in-memory `LibraryRepository` (stub flavor): a 3-disc
 * fixture by default; [seedLarge] builds the 100-disc / 1669-track fixture
 * the plan's scan-scale tests want. `startScan` emits monotonic progress
 * and returns a result over the fixture.
 */
@Singleton
public class FakeLibraryRepository @Inject constructor() : LibraryRepository {

    init {
        StubRuntime.libraryRepository = this
    }

    private val discsFlow = MutableStateFlow(DEFAULT_DISCS)
    private val tracksFlow = MutableStateFlow(DEFAULT_TRACKS)
    private val progress = MutableStateFlow(ScanProgress(0, null))
    private var rootUri: String? = "content://com.android.externalstorage.documents/tree/primary%3AFake"

    override val discs: Flow<List<Disc>> = this.discsFlow

    override fun observeTracks(discId: Long): Flow<List<Track>> =
        tracksFlow.map { it[discId].orEmpty() }

    override suspend fun discById(id: Long): Disc? = discsFlow.value.find { it.id == id }

    override suspend fun tracksOf(discId: Long): List<Track> =
        tracksFlow.value[discId].orEmpty()

    override fun search(query: String): Flow<List<SearchHit>> =
        tracksFlow.map { byDisc ->
            val q = query.lowercase()
            byDisc.entries.flatMap { (discId, tracks) ->
                tracks.filter {
                    it.title.lowercase().contains(q) ||
                        it.performer?.lowercase()?.contains(q) == true ||
                        it.composer?.lowercase()?.contains(q) == true
                }.map { t ->
                    SearchHit(
                        trackId = t.id,
                        discId = t.discId,
                        title = t.title,
                        snippet = listOfNotNull(t.performer, t.composer).joinToString(" · "),
                    )
                }
            }
        }

    override suspend fun distinctComposers(): List<String> =
        tracksFlow.value.values.flatten()
            .mapNotNull { it.composer }
            .distinct()
            .sorted()

    override val scanProgress: Flow<ScanProgress> = progress

    override suspend fun startScan(): ScanResult {
        val allDiscs = discsFlow.value
        val trackCount = tracksFlow.value.values.sumOf { it.size }
        allDiscs.forEachIndexed { i, d ->
            progress.value = ScanProgress(
                discsScanned = i + 1,
                currentFolder = d.folderPath,
            )
        }
        progress.value = ScanProgress(allDiscs.size, null)
        return ScanResult(
            discCount = allDiscs.size,
            trackCount = trackCount,
            durationMs = 25,
            missingCount = 0,
            warnings = emptyList(),
            grantLost = false,
        )
    }

    override suspend fun revokeLibraryAccess() {
        rootUri = null
        discsFlow.value = emptyList()
        tracksFlow.value = emptyMap()
    }

    override suspend fun clearAll() {
        discsFlow.value = emptyList()
        tracksFlow.value = emptyMap()
        progress.value = ScanProgress(0, null)
    }

    /** The 100-disc / 1669-track fixture used by scan-scale tests. */
    public fun seedLarge(discCount: Int = 100, tracksPerDisc: Int = 17) {
        var trackId = 0L
        val d = (1..discCount).map { i -> makeDisc(i, "Sonata album $i") }
        val t = d.associate { disc ->
            val n = if (disc.id.toInt() <= discCount - 1) tracksPerDisc else 19
            disc.id to (1..n).map { no -> makeTrack(++trackId, disc.id, no, "Mov $no") }
        }
        // Trim to exactly 1669 tracks.
        var total = t.values.sumOf { it.size }
        val trimmed = t.toMutableMap()
        var last = d.last().id
        while (total > 1669) {
            trimmed[last] = trimmed[last]!!.dropLast(1)
            total--
        }
        discsFlow.value = d
        tracksFlow.value = trimmed
    }

    public companion object {
        private fun makeDisc(i: Int, title: String): Disc = Disc(
            id = i.toLong(),
            docId = "fake-doc-$i",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AFake",
            folderPath = "Fake/disc$i",
            title = title,
            performer = "Performer ${(i % 3) + 1}",
            composer = "Composer ${(i % 5) + 1}",
            genre = "Classical",
            date = "2008",
            discId = "CD${i.toString().padStart(2, '0')}",
            discNumber = i,
            totalDiscs = 100,
            cueUri = null,
            flacUri = "content://fake/disc$i.flac",
            logUri = null,
            artworkUri = "content://fake/disc$i/cover.jpg",
            albumGainDb = -6.5,
            albumPeak = 0.98,
            flacDurationMs = 3_600_000,
            flacSizeBytes = 200_000_000,
            flacLastModified = 1_700_000_000_000,
            scannedAt = 1_700_000_000_000,
        )

        private fun makeTrack(id: Long, discId: Long, no: Int, title: String): Track = Track(
            id = id,
            discId = discId,
            trackNo = no,
            title = "$title · disc $discId",
            performer = "Performer",
            composer = "Composer ${((no - 1) % 5) + 1}",
            startMs = (no - 1) * 200_000L,
            endMs = no * 200_000L,
            pregapStartMs = null,
            gainDb = -5.0,
            peak = 0.9,
            isrc = null,
            flags = 0,
        )

        private val DEFAULT_DISCS: List<Disc> = (1..3).map { makeDisc(it, "Fake disc $it") }

        private val DEFAULT_TRACKS: Map<Long, List<Track>> =
            DEFAULT_DISCS.associate { disc ->
                disc.id to (1..5).map { no ->
                    makeTrack(disc.id * 100 + no, disc.id, no, "Track $no")
                }
            }
    }
}
