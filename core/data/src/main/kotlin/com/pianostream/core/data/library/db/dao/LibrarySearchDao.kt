package com.pianostream.core.data.library.db.dao

import android.database.sqlite.SQLiteException
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Sole owner of `track_fts` `MATCH` queries (F16). Every term is sanitized
 * — wrapped in `"` with internal `"` doubled, `*^−:` stripped, length-capped —
 * and any residual `SQLiteException` falls back to a `LIKE` scan through
 * [TrackDao.likeSearch], so hostile input can never crash a search.
 */
@Dao
abstract class LibrarySearchDao {

    data class FtsHit(
        val trackId: Long,
        val discId: Long,
        val title: String,
        val snippet: String?,
    )

    @RewriteQueriesToDropUnusedColumns
    @RawQuery(observedEntities = [com.pianostream.core.data.library.db.entity.TrackEntity::class])
    protected abstract suspend fun ftsMatch(query: SupportSQLiteQuery): List<FtsHit>

    suspend fun search(rawQuery: String, trackDao: TrackDao): List<FtsHit> {
        val sanitized = sanitize(rawQuery) ?: return emptyList()
        return try {
            ftsMatch(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT t.id AS trackId, t.discId AS discId, t.title AS title, " +
                        "snippet(track_fts, 0, '[', ']', '…', 16) AS snippet " +
                        "FROM track_fts JOIN tracks t ON t.id = track_fts.docid " +
                        "WHERE track_fts MATCH ? ORDER BY track_fts.docid LIMIT 200",
                    arrayOf(sanitized),
                ),
            )
        } catch (e: SQLiteException) {
            trackDao.likeSearch(rawQuery.take(MAX_QUERY_LEN)).map {
                FtsHit(it.id, it.discId, it.title, null)
            }
        }
    }

    companion object {
        const val MAX_QUERY_LEN = 200

        private val STRIP = Regex("[*^:(){}\\[\\]<>=!&|+~^\"'`\\\\\\-\\u2212]")

        /**
         * Turns arbitrary input into a safe FTS4 phrase query, or `null` for
         * empty/no-term queries.
         */
        fun sanitize(raw: String): String? {
            val cleaned = STRIP.replace(raw.take(MAX_QUERY_LEN), " ")
                .trim().replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) return null
            return "\"" + cleaned.replace("\"", "\"\"") + "\""
        }
    }
}
