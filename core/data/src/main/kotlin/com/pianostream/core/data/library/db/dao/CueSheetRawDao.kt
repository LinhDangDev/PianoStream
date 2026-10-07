package com.pianostream.core.data.library.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pianostream.core.data.library.db.entity.CueSheetRawEntity

@Dao
interface CueSheetRawDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CueSheetRawEntity)

    @Query("SELECT * FROM cue_sheets_raw WHERE discId = :discId")
    suspend fun byDisc(discId: Long): CueSheetRawEntity?

    @Query("DELETE FROM cue_sheets_raw")
    suspend fun deleteAll()
}
