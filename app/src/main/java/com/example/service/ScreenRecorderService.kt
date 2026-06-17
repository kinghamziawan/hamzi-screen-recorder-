package com.example.service

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.db.AppDatabase
import com.example.db.RecordingEntity
import com.example.db.RecordingRepository
import com.example.model.AudioSourceOption
import com.example.model.RecorderConfig
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class ScreenRecorderService : Service() {

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "RecorderServiceChannel"
        
        const val ACTION_START = "com.example.recorder.START"
        const val ACTION_PAUSE = "com.example.recorder.PAUSE"
        const val ACTION_RESUME = "com.example.recorder.RESUME"
        const val ACTION_STOP = "com.example.recorder.STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        
        // Recording state flows for UI sync
        val isRecordingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isPausedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val durationSecondsFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    
    private var tempVideoFile: File? = null
    private var config = RecorderConfig()
    private var startTimeMs: Long = 0
    private var pauseTimeMs: Long = 0
    private var elapsedBeforePause: Long = 0
    private var timerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Floating Views
    private var floatComposeView: ComposeView? = null
    private var cameraOverlayView: FrameLayout? = null
    private val cameraLifecycleOwner = ServiceLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        cameraLifecycleOwner.start()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "Service onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.e(TAG, "Missing projection result parameters")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                config = RecorderConfig.load(this)
                startForegroundServiceCompat()
                
                try {
                    startRecording(resultCode, resultData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed startRecording", e)
                    stopSelf()
                }
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceCompat() {
        val notification = buildNotification(0, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or 
                (if (config.enableFacecam) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and status of the current screen recording"
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(durationSecs: Int, isPaused: Boolean): Notification {
        val minutes = durationSecs / 60
        val seconds = durationSecs % 60
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // Action Intents
        val pauseIntent = Intent(this, ScreenRecorderService::class.java).apply { action = ACTION_PAUSE }
        val resumeIntent = Intent(this, ScreenRecorderService::class.java).apply { action = ACTION_RESUME }
        val stopIntent = Intent(this, ScreenRecorderService::class.java).apply { action = ACTION_STOP }

        val pPause = PendingIntent.getService(this, 1, pauseIntent, pendingIntentFlags)
        val pResume = PendingIntent.getService(this, 2, resumeIntent, pendingIntentFlags)
        val pStop = PendingIntent.getService(this, 3, stopIntent, pendingIntentFlags)

        val mainActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pMain = PendingIntent.getActivity(this, 0, mainActivityIntent, pendingIntentFlags)

        val title = if (isPaused) "Recording Paused ($timeStr)" else "Recording Screen ($timeStr)"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Hamzi Screen Recorder is capturing your screen.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pMain)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(Color.parseColor("#E91E63"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isPaused) {
                builder.addAction(android.R.drawable.ic_media_play, "Resume", pResume)
            } else {
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pPause)
            }
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop)

        return builder.build()
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        tempVideoFile = File(cacheDir, "temp_hamzi_rec_${System.currentTimeMillis()}.mp4")
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            if (config.audioSource != AudioSourceOption.MUTED) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(tempVideoFile!!.absolutePath)
            
            // Set Quality Configurations
            val width = config.resolution.width
            val height = config.resolution.height
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            
            if (config.audioSource != AudioSourceOption.MUTED) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
            }
            
            setVideoEncodingBitRate(config.bitrateMbps * 1000 * 1000)
            setVideoFrameRate(config.fps)
        }

        mediaRecorder?.prepare()

        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        
        // Setup MediaProjection Callback
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection stopped automatically")
                stopRecording()
            }
        }, Handler(Looper.getMainLooper()))

        // Setup Virtual Display
        val dm = resources.displayMetrics
        val density = dm.densityDpi
        val width = config.resolution.width
        val height = config.resolution.height

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HamziScreenRecorderDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null, null
        )

        mediaRecorder?.start()
        
        startTimeMs = System.currentTimeMillis()
        elapsedBeforePause = 0
        isRecordingFlow.value = true
        isPausedFlow.value = false
        durationSecondsFlow.value = 0

        // Start overlay helpers
        if (config.enableFloatingWidget) {
            showFloatingControls()
        }
        if (config.enableFacecam) {
            showFacecam()
        }

        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val durationSecs = if (isPausedFlow.value) {
                    (elapsedBeforePause / 1000).toInt()
                } else {
                    ((elapsedBeforePause + (System.currentTimeMillis() - startTimeMs)) / 1000).toInt()
                }
                durationSecondsFlow.value = durationSecs
                
                // Update persistent notification
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(durationSecs, isPausedFlow.value))
            }
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecordingFlow.value && !isPausedFlow.value) {
            try {
                mediaRecorder?.pause()
                elapsedBeforePause += System.currentTimeMillis() - startTimeMs
                isPausedFlow.value = true
                Log.d(TAG, "Recording paused successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed pause", e)
            }
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecordingFlow.value && isPausedFlow.value) {
            try {
                mediaRecorder?.resume()
                startTimeMs = System.currentTimeMillis()
                isPausedFlow.value = false
                Log.d(TAG, "Recording resumed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed resume", e)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecordingFlow.value) {
            stopSelf()
            return
        }

        timerJob?.cancel()
        isRecordingFlow.value = false
        
        // Calculate duration logic
        val durationSecs = if (isPausedFlow.value) {
            (elapsedBeforePause / 1000).toInt()
        } else {
            ((elapsedBeforePause + (System.currentTimeMillis() - startTimeMs)) / 1000).toInt()
        }

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop failed, file may be empty", e)
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }

        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null

        // Remove floating overlays
        hideFloatingControls()
        hideFacecam()

        // Save recorded raw content to MediaStore (Gallery)
        tempVideoFile?.let { rawFile ->
            if (rawFile.exists() && rawFile.length() > 0) {
                serviceScope.launch(Dispatchers.IO) {
                    saveToGalleryAndDatabase(rawFile, durationSecs)
                }
            }
        }

        stopForeground(true)
        stopSelf()
    }

    private suspend fun saveToGalleryAndDatabase(file: File, durationSecs: Int) {
        val timestamp = System.currentTimeMillis()
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
        val filename = "HamziRec_$format.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, timestamp / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, timestamp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HamziScreenRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val contentResolver = applicationContext.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values)

        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    FileInputStream(file).use { iStream ->
                        iStream.copyTo(os)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }

                // Add Room database metadata entry
                val database = AppDatabase.getDatabase(this@ScreenRecorderService)
                val repository = RecordingRepository(database.recordingDao())
                val durationMs = durationSecs * 1000L
                val resolutionStr = "${config.resolution.width}x${config.resolution.height}"
                
                repository.insert(
                    RecordingEntity(
                        title = filename,
                        filePath = file.absolutePath,
                        uriString = uri.toString(),
                        durationMs = durationMs,
                        fileSize = file.length(),
                        timestamp = timestamp,
                        resolution = resolutionStr
                    )
                )
                Log.d(TAG, "Recording saved cleanly in media and Room. Uri: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing video file to MediaStore collection", e)
            } finally {
                // Delete temporary workspace file
                file.delete()
            }
        }
    }

    // --- Floating Draggable Controls Widget Overlay ---
    private fun showFloatingControls() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 350
        }

        val ctx = this
        floatComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var offsetX by remember { mutableStateOf(params.x.toFloat()) }
                var offsetY by remember { mutableStateOf(params.y.toFloat()) }
                
                val recState by isRecordingFlow.collectAsState()
                val isPausedState by isPausedFlow.collectAsState()
                val secondsState by durationSecondsFlow.collectAsState()

                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                                params.x = offsetX.roundToInt()
                                params.y = offsetY.roundToInt()
                                windowManager.updateViewLayout(floatComposeView, params)
                            }
                        }
                ) {
                    FloatingControlsContent(
                        durationSecs = secondsState,
                        isPaused = isPausedState,
                        onPauseToggle = {
                            if (isPausedState) {
                                resumeRecording()
                            } else {
                                pauseRecording()
                            }
                        },
                        onStop = { stopRecording() }
                    )
                }
            }
        }

        floatComposeView?.let {
            try {
                windowManager.addView(it, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject floating controls to WindowManager", e)
            }
        }
    }

    private fun hideFloatingControls() {
        floatComposeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed removinf floatComposeView", e)
            }
            floatComposeView = null
        }
    }

    // --- Front Camera Overlay (Facecam) ---
    private fun showFacecam() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val sizePx = (120 * resources.displayMetrics.density).roundToInt()
        
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 150
        }

        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        cameraOverlayView = FrameLayout(this).apply {
            // Apply a circular visual theme container
            background = ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_dark_frame)
            clipToOutline = true
            
            // Add Preview View
            addView(previewView)
            
            // Handle touch drag
            var startX = 0f
            var startY = 0f
            var dragX = params.x.toFloat()
            var dragY = params.y.toFloat()
            
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        dragX = params.x.toFloat()
                        dragY = params.y.toFloat()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        // Flip y calculation for bottom aligned layout
                        params.x = (dragX - dx).roundToInt()
                        params.y = (dragY - dy).roundToInt()
                        try {
                            windowManager.updateViewLayout(cameraOverlayView, params)
                        } catch (e: Exception) {}
                        true
                    }
                    else -> false
                }
            }
        }

        cameraOverlayView?.let {
            try {
                windowManager.addView(it, params)
                startCameraX(previewView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display Facecam cameraOverlayView", e)
            }
        }
    }

    private fun startCameraX(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(cameraLifecycleOwner, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "CameraX setup failure", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideFacecam() {
        cameraOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            cameraOverlayView = null
        }
        cameraLifecycleOwner.stop()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        hideFloatingControls()
        hideFacecam()
        super.onDestroy()
    }
}

// Draggable Floating Widget UI
@Composable
fun FloatingControlsContent(
    durationSecs: Int,
    isPaused: Boolean,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit
) {
    val minutes = durationSecs / 60
    val seconds = durationSecs % 60
    val timerText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier
            .width(180.dp)
            .padding(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // Pulsing live indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPaused) MaterialTheme.colorScheme.outline
                            else androidx.compose.ui.graphics.Color.Red
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = timerText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions Block
            Row(horizontalArrangement = Arrangement.End) {
                // Pause/Resume button
                IconButton(
                    onClick = onPauseToggle,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = "Pause or Resume",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Stop recording button
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(32.dp)
                        .background(androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Capture",
                        tint = androidx.compose.ui.graphics.Color.Red,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// Custom LifecycleOwner inside background service for safe compilation binds
class ServiceLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
