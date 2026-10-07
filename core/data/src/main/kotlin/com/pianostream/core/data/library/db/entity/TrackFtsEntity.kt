package com.pianostream.core.data.library.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * `track_fts` — FTS4 content-synced with [TrackEntity] over
 * title/performer/composer. Only `LibrarySearchDao` issues `MATCH` against
 * it (F16 — sanitized phrase queries + LIKE fallback).
 */
@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "track_fts")
data class TrackFtsEntity(
    val title: String,
    val performer: String?,
    val composer: String?,
)
