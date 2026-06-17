package com.example.model

import android.content.Context
import android.util.DisplayMetrics

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    RES_720P("720p (HD)", 1280, 720),
    RES_1080P("1080p (Full HD)", 1920, 1080),
    RES_4K("4K (Ultra HD)", 3840, 2160)
}

enum class AudioSourceOption(val label: String) {
    MIC("Microphone Audio"),
    INTERNAL("Internal Device Audio"),
    BOTH("Mic & Internal Combined"),
    MUTED("No Audio (Mute)")
}

data class RecorderConfig(
    val resolution: VideoResolution = VideoResolution.RES_1080P,
    val fps: Int = 30,
    val bitrateMbps: Int = 8,
    val audioSource: AudioSourceOption = AudioSourceOption.MIC,
    val enableFacecam: Boolean = false,
    val enableFloatingWidget: Boolean = true
) {
    companion object {
        fun load(context: Context): RecorderConfig {
            val prefs = context.getSharedPreferences("recorder_prefs", Context.MODE_PRIVATE)
            val resName = prefs.getString("resolution", VideoResolution.RES_1080P.name) ?: VideoResolution.RES_1080P.name
            val resolution = try { VideoResolution.valueOf(resName) } catch(e: Exception) { VideoResolution.RES_1080P }
            
            return RecorderConfig(
                resolution = resolution,
                fps = prefs.getInt("fps", 30),
                bitrateMbps = prefs.getInt("bitrateMbps", 8),
                audioSource = try { AudioSourceOption.valueOf(prefs.getString("audio_source", AudioSourceOption.MIC.name) ?: AudioSourceOption.MIC.name) } catch(e: Exception) { AudioSourceOption.MIC },
                enableFacecam = prefs.getBoolean("enable_facecam", false),
                enableFloatingWidget = prefs.getBoolean("enable_floating_widget", true)
            )
        }

        fun save(context: Context, config: RecorderConfig) {
            val prefs = context.getSharedPreferences("recorder_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("resolution", config.resolution.name)
                .putInt("fps", config.fps)
                .putInt("bitrateMbps", config.bitrateMbps)
                .putString("audio_source", config.audioSource.name)
                .putBoolean("enable_facecam", config.enableFacecam)
                .putBoolean("enable_floating_widget", config.enableFloatingWidget)
                .apply()
        }
    }
}
