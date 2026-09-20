package com.proofstamp.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity)

    @Update
    suspend fun update(photo: PhotoEntity)

    @Delete
    suspend fun delete(photo: PhotoEntity)

    @Query("SELECT * FROM photos ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observeById(id: String): Flow<PhotoEntity?>

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE contentHash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE verificationCode = :code LIMIT 1")
    suspend fun getByCode(code: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun getBySession(sessionId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE sessionId = :sessionId ORDER BY sequence ASC")
    fun observeBySession(sessionId: String): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photos WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    @Query("SELECT DISTINCT project FROM photos WHERE project != '' ORDER BY project ASC")
    fun observeProjects(): Flow<List<String>>

    @Query("UPDATE photos SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("UPDATE sessions SET photoCount = :count WHERE id = :id")
    suspend fun updateCount(id: String, count: Int)

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun end(id: String, endedAt: Long)
}

@Dao
interface PresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity): Long

    @Delete
    suspend fun delete(preset: PresetEntity)

    @Query("SELECT * FROM presets ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getById(id: Long): PresetEntity?

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int
}
