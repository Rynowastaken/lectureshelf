package com.ryno.classrecorder.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ryno.classrecorder.service.RecordingService
import org.json.JSONObject
import java.io.File

/**
 * Process-wide [RecordingBridge] and idempotent [RustCore] initializer so both
 * [com.ryno.classrecorder.ui.MainViewModel] and [RecordingService] can safely access
 * SQLite storage even if the service starts or stops in a fresh process.
 */
object AppRecordingBridge : RecordingBridge {
    @Volatile
    private var appContext: Context? = null

    fun ensureCoreInitialized(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val primaryParent = ctx.getExternalFilesDir(null)
        val primaryDir = primaryParent?.let { File(it, "Recordings") }
        val recordingsDir = if (primaryDir != null && (primaryDir.exists() || primaryDir.mkdirs()) && primaryDir.canWrite()) {
            primaryDir
        } else {
            File(ctx.filesDir, "Recordings").apply { mkdirs() }
        }
        RustCore.initCore(ctx.filesDir.absolutePath, recordingsDir.absolutePath, this)
    }

    override fun startRecordingFromAgent(courseJson: String): String {
        val ctx = appContext
            ?: return JSONObject().put("error", "App context not initialized").toString()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("error", "Microphone permission not granted on device").toString()
        }
        if (RecordingService.state.value.isRecording) {
            return JSONObject().put("error", "A recording is already active").toString()
        }
        RecordingService.start(ctx, courseJson)
        for (i in 0 until 20) {
            Thread.sleep(100)
            val active = RecordingService.state.value.activeRecording
            if (RecordingService.state.value.isRecording && active != null) {
                return JSONObject().put("recording", active.toJsonObject()).toString()
            }
        }
        return JSONObject().put("error", "Timed out waiting for RecordingService to start").toString()
    }

    override fun stopRecordingFromAgent(): String {
        val ctx = appContext
            ?: return JSONObject().put("error", "App context not initialized").toString()
        val active = RecordingService.state.value.activeRecording
            ?: return JSONObject().put("error", "No active recording").toString()
        RecordingService.stop(ctx)
        for (i in 0 until 20) {
            Thread.sleep(100)
            if (!RecordingService.state.value.isRecording) {
                val res = JSONObject(RustCore.listRecordings(""))
                val list = JsonModels.parseRecordings(res.optJSONArray("data"))
                val updated = list.firstOrNull { it.id == active.id } ?: active.copy(status = "completed")
                return JSONObject().put("recording", updated.toJsonObject()).toString()
            }
        }
        return JSONObject().put("error", "Timed out waiting for RecordingService to stop").toString()
    }
}
