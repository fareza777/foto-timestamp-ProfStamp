package com.proofstamp.app.data.repo

import android.content.Context
import com.proofstamp.app.data.crypto.Ids
import com.proofstamp.app.data.db.PhotoDao
import com.proofstamp.app.data.db.PhotoEntity
import com.proofstamp.app.data.db.PresetDao
import com.proofstamp.app.data.db.PresetEntity
import com.proofstamp.app.data.db.SessionDao
import com.proofstamp.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class PhotoRepository(private val context: Context, private val dao: PhotoDao) {
    val capturesDir: File get() = File(context.filesDir, "captures").apply { mkdirs() }

    fun observeAll(): Flow<List<PhotoEntity>> = dao.observeAll()
    fun observeById(id: String): Flow<PhotoEntity?> = dao.observeById(id)
    fun observeProjects(): Flow<List<String>> = dao.observeProjects()
    fun observeBySession(sessionId: String): Flow<List<PhotoEntity>> = dao.observeBySession(sessionId)

    suspend fun getById(id: String) = dao.getById(id)
    suspend fun getByIds(ids: List<String>) = dao.getByIds(ids)
    suspend fun getByContentHash(hash: String) = dao.getByContentHash(hash)
    suspend fun getByCode(code: String) = dao.getByCode(Ids.normalizeCode(code))
    suspend fun getBySession(sessionId: String) = dao.getBySession(sessionId)
    suspend fun insert(photo: PhotoEntity) = dao.insert(photo)
    suspend fun updateNote(id: String, note: String) = dao.updateNote(id, note)

    suspend fun delete(photo: PhotoEntity) = withContext(Dispatchers.IO) {
        File(photo.filePath).delete()
        dao.delete(photo)
    }
}

class SessionRepository(private val sessionDao: SessionDao, private val photoDao: PhotoDao) {
    fun observeAll(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    fun observeActive(): Flow<SessionEntity?> = sessionDao.observeActive()
    fun observeById(id: String): Flow<SessionEntity?> = sessionDao.observeById(id)
    suspend fun getById(id: String) = sessionDao.getById(id)

    suspend fun start(name: String, project: String, operator: String): SessionEntity {
        val session = SessionEntity(
            id = Ids.sessionId(),
            name = name.trim().ifBlank { "Session" },
            project = project,
            operator = operator,
            startedAt = System.currentTimeMillis(),
            endedAt = null,
            photoCount = 0,
        )
        sessionDao.upsert(session)
        return session
    }

    suspend fun end(id: String) = sessionDao.end(id, System.currentTimeMillis())

    /** Returns the next sequence number (1-based) and bumps the stored count. */
    suspend fun nextSequence(sessionId: String): Int {
        val next = photoDao.countInSession(sessionId) + 1
        sessionDao.updateCount(sessionId, next)
        return next
    }

    suspend fun refreshCount(sessionId: String) = sessionDao.updateCount(sessionId, photoDao.countInSession(sessionId))

    suspend fun delete(session: SessionEntity) = sessionDao.delete(session)
}

class PresetRepository(private val dao: PresetDao) {
    fun observeAll(): Flow<List<PresetEntity>> = dao.observeAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun upsert(preset: PresetEntity): Long = dao.upsert(preset)
    suspend fun delete(preset: PresetEntity) = dao.delete(preset)
}
