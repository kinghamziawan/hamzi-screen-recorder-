package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.RecordingEntity
import com.example.db.RecordingRepository
import com.example.model.AudioSourceOption
import com.example.model.RecorderConfig
import com.example.model.VideoResolution
import com.example.service.ScreenRecorderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val repository = RecordingRepository(database.recordingDao())

    // App recordings reactive data
    val recordings: StateFlow<List<RecordingEntity>> = repository.allRecordings
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Config preferences reactive state
    private val _configState = MutableStateFlow(RecorderConfig.load(context))
    val configState: StateFlow<RecorderConfig> = _configState.asStateFlow()

    // Recording Service states
    val isRecording: StateFlow<Boolean> = ScreenRecorderService.isRecordingFlow
    val isPaused: StateFlow<Boolean> = ScreenRecorderService.isPausedFlow
    val durationSeconds: StateFlow<Int> = ScreenRecorderService.durationSecondsFlow

    // Storage Status States
    private val _freeStorageBytes = MutableStateFlow(0L)
    val freeStorageBytes: StateFlow<Long> = _freeStorageBytes.asStateFlow()

    private val _totalStorageBytes = MutableStateFlow(0L)
    val totalStorageBytes: StateFlow<Long> = _totalStorageBytes.asStateFlow()

    init {
        updateStorageStats()
    }

    fun updateStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = Environment.getDataDirectory()
                val stat = StatFs(path.path)
                val blockSize = stat.blockSizeLong
                val availableBlocks = stat.availableBlocksLong
                val totalBlocks = stat.blockCountLong

                _freeStorageBytes.value = availableBlocks * blockSize
                _totalStorageBytes.value = totalBlocks * blockSize
            } catch (e: Exception) {
                _freeStorageBytes.value = 0L
                _totalStorageBytes.value = 0L
            }
        }
    }

    // Load fresh setup
    fun reloadConfig() {
        _configState.value = RecorderConfig.load(context)
    }

    // Save configurations
    fun updateResolution(resolution: VideoResolution) {
        val updated = _configState.value.copy(resolution = resolution)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    fun updateFps(fps: Int) {
        val updated = _configState.value.copy(fps = fps)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    fun updateBitrate(bitrate: Int) {
        val updated = _configState.value.copy(bitrateMbps = bitrate)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    fun updateAudioSource(source: AudioSourceOption) {
        val updated = _configState.value.copy(audioSource = source)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    fun updateFacecam(enabled: Boolean) {
        val updated = _configState.value.copy(enableFacecam = enabled)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    fun updateFloatingWidget(enabled: Boolean) {
        val updated = _configState.value.copy(enableFloatingWidget = enabled)
        _configState.value = updated
        RecorderConfig.save(context, updated)
    }

    // Room operations
    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete record in DB
            repository.delete(recording.id)
            
            // Delete file in storage if it exists there
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // file delete skip if inaccessible
            }
        }
    }

    fun renameRecording(recording: RecordingEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = recording.copy(title = newName)
            repository.update(updated)
        }
    }

    fun deleteAllRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
        }
    }

    // Storage capacity helper (remaining recording time in minutes)
    val remainingRecordingTimeMinutes: StateFlow<Long> = _freeStorageBytes.map { freeBytes ->
        val currentBitrateBytesPerSec = (_configState.value.bitrateMbps * 1024 * 1024) / 8L
        if (currentBitrateBytesPerSec <= 0) return@map 999L
        
        val durationSecs = freeBytes / currentBitrateBytesPerSec
        durationSecs / 60L
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0L
    )
}
