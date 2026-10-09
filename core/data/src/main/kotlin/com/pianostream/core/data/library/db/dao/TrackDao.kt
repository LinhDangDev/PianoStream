package com.pianostream.core.data.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pianostream.core.data.library.db.ArtistTrackRow
import com.pianostream.core.data.library.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE discId = :discId ORDER BY trackNo")
    fun observeByDisc(discId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE discId = :discId ORDER BY trackNo")
    suspend fun byDisc(discId: Long): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE discId = :discId")
    suspend fun deleteByDisc(discId: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    /** LIKE fallback used when FTS sanitize refuses or MATCH errors (F16). */
    @Query(
        "SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR " +
            "performer LIKE '%' || :q || '%' OR composer LIKE '%' || :q || '%' " +
            "ORDER BY trackNo",
    )
    suspend fun likeSearch(q: String): List<TrackEntity>

    @Query(
        "SELECT DISTINCT composer FROM tracks WHERE composer IS NOT NULL AND composer != '' " +
            "ORDER BY composer",
    )
    suspend fun distinctComposers(): List<String>

    /**
     * Tracks credited to [artist] for the artist page: the track's own
     * PERFORMER when present, else the parent disc's PERFORMER (cue sheets
     * inherit the album tag), else the track's SONGWRITER — plus explicit
     * `composer` matches so taps from the Composers tab land on a populated
     * page. Rows join the disc's title/folderPath/artworkUri; `indexInCue`
     * is the 0-based cue ordinal (position inside the disc's `ORDER BY
     * trackNo` list — `UNIQUE(discId, trackNo)` makes the count exact).
     */
    @Query(
        "SELECT t.*, d.folderPath AS discFolderPath, d.title AS discTitle, " +
            "d.artworkUri AS discArtworkUri, " +
            "(SELECT COUNT(*) FROM tracks t2 WHERE t2.discId = t.discId " +
            "AND t2.trackNo < t.trackNo) AS indexInCue " +
            "FROM tracks t INNER JOIN discs d ON t.discId = d.id " +
            "WHERE COALESCE(NULLIF(t.performer, ''), d.performer, t.composer) = :artist " +
            "OR t.composer = :artist " +
            "ORDER BY d.folderPath, t.trackNo",
    )
    fun observeArtistTracks(artist: String): Flow<List<ArtistTrackRow>>
}
