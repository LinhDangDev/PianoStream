package com.pianostream.core.data.library

import com.pianostream.core.data.library.model.ArtistTrack
import com.pianostream.core.data.library.model.Disc
import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult
import com.pianostream.core.data.library.model.SearchHit
import com.pianostream.core.data.library.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `library.db` owner (Interface Freeze; Phase 3 implements
 * `LibraryRepositoryImpl` + `library/db/`**` + `library/scan/`**`).
 *
 * Contracts baked in:
 *  - **CC7** — `Disc.composer` is the distinct aggregate of track `SONGWRITER`s;
 *    [distinctComposers] is frozen for the library "Composers" browse.
 *  - **F16** — [search] goes through `LibrarySearchDao` only (sanitized FTS).
 *  - **F8** — [revokeLibraryAccess] calls `releasePersistableUriPermission`
 *    and clears `libraryRootTreeUri`; SAF grants are read-only, never WRITE.
 *  - **M2** — a lost `SecurityException` mid-scan aborts and reports
 *    `grantLost`, never a partial-success lie.
 *  - **CC10** — [clearAll] is the `library.db` half of `deleteAllLocalData()`.
 */
public interface LibraryRepository {

    /** All scanned discs, ordered for display. Emits on every change. */
    public val discs: Flow<List<Disc>>

    /** Tracks of one disc ordered by `trackNo`. */
    public fun observeTracks(discId: Long): Flow<List<Track>>

    public suspend fun discById(id: Long): Disc?

    /** One-shot (non-flow) track list for a disc. */
    public suspend fun tracksOf(discId: Long): List<Track>

    /** FTS search over title/performer/composer via `LibrarySearchDao` (F16). */
    public fun search(query: String): Flow<List<SearchHit>>

    /** Distinct disc composers (CC7 aggregate). */
    public suspend fun distinctComposers(): List<String>

    /**
     * Discs credited to [artist] (disc `performer` tag) or holding tracks
     * performed/composed by them — the artist page's "Discs" section.
     * The empty default keeps frozen stub/test fakes compiling.
     */
    public fun observeArtistDiscs(artist: String): Flow<List<Disc>> = flowOf(emptyList())

    /**
     * Tracks whose effective artist is [artist]: track `performer`, else the
     * disc's `performer` (cue tag inheritance), else the track's `composer`
     * — plus explicit composer matches (Composers-tab taps share this
     * route). Rows carry disc context + the cue ordinal for
     * `LibraryHasher.mediaId` (CC2). Empty default as above.
     */
    public fun observeArtistTracks(artist: String): Flow<List<ArtistTrack>> = flowOf(emptyList())

    /** Emits `ScanProgress` while a scan runs; idle otherwise. */
    public val scanProgress: Flow<ScanProgress>

    /**
     * Runs a full scan of the granted tree (or a no-op result when no tree is
     * granted). Throws nothing for per-disc failures — they land in
     * [ScanResult.warnings]; a lost grant yields [ScanResult.grantLost] (M2).
     */
    public suspend fun startScan(): ScanResult

    /**
     * Releases the persisted SAF URI permission and clears `libraryRootTreeUri`
     * (F8). Does **not** wipe rows — pair with a rescan or [clearAll].
     */
    public suspend fun revokeLibraryAccess()

    /**
     * Wipes `library.db` (+ its `-wal`/`-shm`) — the library half of the
     * canonical `deleteAllLocalData()` action (CC10).
     */
    public suspend fun clearAll()
}
