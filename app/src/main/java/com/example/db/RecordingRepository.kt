package com.example.db

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<RecordingEntity>> = recordingDao.getAllRecordings()

    suspend fun insert(recording: RecordingEntity) {
        recordingDao.insertRecording(recording)
    }

    suspend fun delete(id: Int) {
        recordingDao.deleteRecording(id)
    }

    suspend fun update(recording: RecordingEntity) {
        recordingDao.updateRecording(recording)
    }

    suspend fun deleteAll() {
        recordingDao.deleteAll()
    }
}
