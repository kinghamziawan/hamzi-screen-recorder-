package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recording_history")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val filePath: String,
    val uriString: String,
    val durationMs: Long,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val resolution: String
)
