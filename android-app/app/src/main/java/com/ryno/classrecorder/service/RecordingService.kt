package com.ryno.classrecorder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.ryno.classrecorder.MainActivity
import com.ryno.classrecorder.R
import com.ryno.classrecorder.core.AppRecordingBridge
import com.ryno.classrecorder.core.JsonModels
import com.ryno.classrecorder.core.RecordingFailsafe
import com.ryno.classrecorder.core.RecordingRecord
import com.ryno.classrecorder.core.RustCore
import com.ryno.classrecorder.ui.screens.UiFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecorderState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val activeRecording: RecordingRecord? = null
)

class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lifecycleLock = Any()
    private var recorder: MediaRecorder? = null
    private var activeOutputPath: String? = null
    private var tickerJob: Job? = null
    private var checkpointJob: Job? = null
    private var startEpochMs: Long = 0L
    private var accumulatedMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        installCrashFailsafe()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val courseJson = intent.getStringExtra(EXTRA_COURSE_JSON) ?: "{}"
                startInternal(courseJson)
            }
            ACTION_STOP -> stopInternal()
            ACTION_DISCARD -> discardInternal()
            ACTION_TOGGLE_PAUSE -> togglePauseInternal()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (_state.value.isRecording) {
            stopInternal()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun installCrashFailsafe() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                activeInstance?.let { service ->
                    if (_state.value.isRecording) {
                        service.stopInternal()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash failsafe failed while saving recording", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun startInternal(courseJson: String) = synchronized(lifecycleLock) {
        if (_state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        AppRecordingBridge.ensureCoreInitialized(this)

        val prepRaw = RustCore.prepareRecording(courseJson)
        val prepObj = JSONObject(prepRaw)
        if (!prepObj.optBoolean("ok", false)) return
        val rec = JsonModels.parseRecording(prepObj.getJSONObject("data"))

        createNotificationChannel()
        val notification = buildNotification(rec.courseName, isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationIdFor(this),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(notificationIdFor(this), notification)
        }

        val fallbackPath = File(filesDir, "Recordings/Fallback/${File(rec.filePath).name}").absolutePath
        val started = startRecorderWithFallback(rec.filePath)
            ?: startRecorderWithFallback(fallbackPath)

        if (started == null) {
            recorder?.release()
            recorder = null
            activeOutputPath = null
            RustCore.deleteRecording(rec.id)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recorder = started.first
        activeOutputPath = started.second
        activeInstance = this
        accumulatedMs = 0L
        startEpochMs = System.currentTimeMillis()
        RecordingFailsafe.saveCheckpoint(filesDir, rec.id, rec.filePath, 0L)
        _state.value = RecorderState(
            isRecording = true,
            isPaused = false,
            elapsedMs = 0L,
            amplitude = 0f,
            activeRecording = rec
        )
        startTicker()
        startCheckpointLoop()
    }

    private fun startRecorderWithFallback(outputPath: String): Pair<MediaRecorder, String>? {
        val configs = listOf(
            RecorderConfig(MediaRecorder.AudioSource.MIC, 128_000, 44_100),
            RecorderConfig(MediaRecorder.AudioSource.DEFAULT, 64_000, 16_000)
        )
        for (cfg in configs) {
            var mr: MediaRecorder? = null
            try {
                File(outputPath).parentFile?.mkdirs()
                mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                mr.setAudioSource(cfg.audioSource)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(cfg.bitRate)
                mr.setAudioSamplingRate(cfg.sampleRate)
                mr.setOutputFile(outputPath)
                mr.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error what=$what extra=$extra; saving partial recording")
                    stopInternal()
                }
                mr.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    ) {
                        Log.w(TAG, "MediaRecorder limit reached ($what); finalizing recording")
                        stopInternal()
                    }
                }
                mr.prepare()
                mr.start()
                return mr to outputPath
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder start failed for $outputPath ($cfg): ${e.message}")
                runCatching { mr?.release() }
            }
        }
        return null
    }

    private data class RecorderConfig(
        val audioSource: Int,
        val bitRate: Int,
        val sampleRate: Int
    )

    private fun togglePauseInternal() = synchronized(lifecycleLock) {
        val current = _state.value
        if (!current.isRecording) return
        try {
            if (current.isPaused) {
                recorder?.resume()
                startEpochMs = System.currentTimeMillis()
                _state.value = current.copy(isPaused = false)
            } else {
                recorder?.pause()
                accumulatedMs += System.currentTimeMillis() - startEpochMs
                _state.value = current.copy(
                    isPaused = true,
                    elapsedMs = accumulatedMs,
                    amplitude = 0f
                )
            }
            current.activeRecording?.let { rec ->
                val elapsed = if (_state.value.isPaused) accumulatedMs
                else accumulatedMs + (System.currentTimeMillis() - startEpochMs)
                RecordingFailsafe.saveCheckpoint(filesDir, rec.id, rec.filePath, elapsed)
            }
            val courseName = current.activeRecording?.courseName ?: "Class Recording"
            val elapsed = if (_state.value.isPaused) accumulatedMs
            else accumulatedMs + (System.currentTimeMillis() - startEpochMs)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationIdFor(this), buildNotification(courseName, _state.value.isPaused, elapsed))
        } catch (_: Exception) {
        }
    }

    private fun stopInternal() = synchronized(lifecycleLock) {
        val current = _state.value
        if (!current.isRecording) return

        tickerJob?.cancel()
        checkpointJob?.cancel()
        val totalMs = if (current.isPaused) {
            accumulatedMs
        } else {
            accumulatedMs + (System.currentTimeMillis() - startEpochMs)
        }.coerceAtLeast(0L)

        releaseRecorder()
        val recordedPath = activeOutputPath
        activeOutputPath = null
        activeInstance = null

        current.activeRecording?.let { rec ->
            if (recordedPath != null && recordedPath != rec.filePath) {
                val src = File(recordedPath)
                val dst = File(rec.filePath)
                if (src.exists() && src.length() > 0L) {
                    runCatching {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                    }
                }
            }
            val finalPath = if (File(rec.filePath).let { it.exists() && it.length() > 0L }) {
                rec.filePath
            } else {
                recordedPath ?: rec.filePath
            }
            val probedMs = RecordingFailsafe.probeAudioDurationMs(finalPath) ?: 0L
            val finalDurationMs = maxOf(totalMs, probedMs)
            RecordingFailsafe.completeWithRetry(this, rec.id, finalDurationMs)
        }

        _state.value = RecorderState()
        _finishedEvents.tryEmit(Unit)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun discardInternal() = synchronized(lifecycleLock) {
        val current = _state.value
        if (!current.isRecording) return

        tickerJob?.cancel()
        checkpointJob?.cancel()
        releaseRecorder()
        val recordedPath = activeOutputPath
        activeOutputPath = null
        activeInstance = null

        current.activeRecording?.let { rec ->
            RecordingFailsafe.clearCheckpoint(filesDir, rec.id)
            if (recordedPath != null && recordedPath != rec.filePath) {
                runCatching { File(recordedPath).delete() }
            }
            AppRecordingBridge.ensureCoreInitialized(this)
            RustCore.deleteRecording(rec.id)
        }

        _state.value = RecorderState()
        _finishedEvents.tryEmit(Unit)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder.stop() threw; preserving file on disk: ${e.message}")
        } finally {
            runCatching { recorder?.release() }
            recorder = null
        }
    }

    private fun startCheckpointLoop() {
        checkpointJob?.cancel()
        checkpointJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
                val cur = _state.value
                if (!cur.isRecording) break
                val rec = cur.activeRecording ?: continue
                val elapsed = if (cur.isPaused) accumulatedMs
                else accumulatedMs + (System.currentTimeMillis() - startEpochMs)
                RecordingFailsafe.saveCheckpoint(filesDir, rec.id, rec.filePath, elapsed)
            }
        }
    }

    /**
     * Samples live mic amplitude at ~30 fps ONLY while at least one foreground UI
     * subscriber is observing [state]. When the screen is off or the app is in the
     * background (`subscriptionCount == 0`), this coroutine suspends with zero CPU
     * wakeups while MediaRecorder and the system Chronometer continue uninterrupted.
     */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                if (_state.subscriptionCount.value == 0) {
                    _state.subscriptionCount.first { it > 0 }
                }
                val cur = _state.value
                if (!cur.isRecording) break
                if (!cur.isPaused) {
                    val elapsed = accumulatedMs + (System.currentTimeMillis() - startEpochMs)
                    val maxAmp = try {
                        (recorder?.maxAmplitude ?: 0) / 32767f
                    } catch (_: Exception) {
                        0f
                    }
                    _state.value = cur.copy(
                        elapsedMs = elapsed,
                        amplitude = maxAmp.coerceIn(0f, 1f)
                    )
                }
                delay(33)
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Recording",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Live recording status and timer"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(
        courseName: String,
        isPaused: Boolean,
        elapsedMs: Long = 0L
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val discardIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RecordingService::class.java).apply { action = ACTION_DISCARD },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val shortChip = if (isPaused) "Paused" else UiFormatters.formatDuration(elapsedMs)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPaused) "Paused • $courseName" else "Recording • $courseName")
            .setContentText(if (isPaused) "Tap Resume to continue" else "Saving to $courseName/")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFB3261E.toInt())
            .setColorized(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setUsesChronometer(!isPaused)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (isPaused) R.drawable.ic_notif_resume else R.drawable.ic_notif_pause
                    ),
                    if (isPaused) "Resume" else "Pause",
                    pauseIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif_stop),
                    "Stop",
                    stopIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif_delete),
                    "Delete",
                    discardIntent
                ).build()
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val extras = Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
            }
            builder.addExtras(extras)
            builder.setShortCriticalText(shortChip)
        }

        builder.addExtras(
            buildHyperOsFocusExtras(
                courseName = courseName,
                isPaused = isPaused,
                elapsedMs = elapsedMs,
                pauseIntent = pauseIntent,
                stopIntent = stopIntent
            )
        )

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            notification.flags = notification.flags or Notification.FLAG_PROMOTED_ONGOING
        }
        return notification
    }

    private fun buildHyperOsFocusExtras(
        courseName: String,
        isPaused: Boolean,
        elapsedMs: Long,
        pauseIntent: PendingIntent,
        stopIntent: PendingIntent
    ): Bundle {
        val now = System.currentTimeMillis()
        val timerWhen = now - elapsedMs
        val timerType = if (isPaused) 2 else 1
        val title = if (isPaused) "Paused • $courseName" else "Recording • $courseName"
        val content = if (isPaused) "Tap Resume to continue" else UiFormatters.formatDuration(elapsedMs)

        val picIcon = Icon.createWithResource(this, R.mipmap.ic_launcher_round)
        val picsBundle = Bundle().apply {
            putParcelable("miui.focus.pic_1", picIcon)
            putParcelable("miui.focus.pic_2", picIcon)
        }

        val pauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isPaused) R.drawable.ic_notif_resume else R.drawable.ic_notif_pause
            ),
            if (isPaused) "Resume" else "Pause",
            pauseIntent
        ).addExtras(
            Bundle().apply {
                putString("icon_name", if (isPaused) "action_restart" else "action_pause")
            }
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_stop),
            "Stop",
            stopIntent
        ).addExtras(
            Bundle().apply {
                putString("icon_name", "action_done")
            }
        ).build()

        val actionsBundle = Bundle().apply {
            putParcelable("miui.focus.action_1", pauseAction)
            putParcelable("miui.focus.action_2", stopAction)
        }

        val timerInfo = JSONObject().apply {
            put("timerType", timerType)
            put("timerWhen", timerWhen)
            put("timerSystemCurrent", now)
        }

        val bigIslandArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().apply {
                    put("type", 1)
                    put(
                        "picInfo",
                        JSONObject().apply {
                            put("type", 2)
                            put("pic", "voiceWaveSmall")
                            put("loop", !isPaused)
                            put("autoplay", !isPaused)
                        }
                    )
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("title", courseName)
                            put("content", if (isPaused) "Paused" else "Recording")
                        }
                    )
                }
            )
            put(
                "sameWidthDigitInfo",
                JSONObject().apply {
                    put("timerInfo", timerInfo)
                }
            )
        }

        val smallIslandArea = JSONObject().apply {
            put(
                "picInfo",
                if (!isPaused) {
                    JSONObject().apply {
                        put("type", 2)
                        put("pic", "voiceWaveSmall")
                        put("loop", true)
                        put("autoplay", true)
                    }
                } else {
                    JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_2")
                    }
                }
            )
        }

        val paramIsland = JSONObject().apply {
            put("islandPriority", 1)
            put("islandTimeout", if (isPaused) 600 else 43200)
            put("islandProperty", 2)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
        }

        val actionsArray = JSONArray().apply {
            put(JSONObject().put("type", 0).put("action", "miui.focus.action_1"))
            put(JSONObject().put("type", 0).put("action", "miui.focus.action_2"))
        }

        val animTextInfo = JSONObject().apply {
            put(
                "animIconInfo",
                JSONObject().apply {
                    put("number", 0)
                    put("src", "voiceWaveBig")
                    put("loop", !isPaused)
                    put("type", 1)
                    put("autoplay", !isPaused)
                }
            )
            put("title", title)
            put("content", content)
            put("timerInfo", timerInfo)
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "audio_recording")
            put("notifyId", "${packageName}100")
            put("scene", "recorder")
            put("title", title)
            put("content", content)
            put("timerType", timerType)
            put("timerWhen", timerWhen)
            put("timerSystemCurrent", now)
            put("updatable", true)
            put("enableFloat", false)
            put("showSmallIcon", false)
            put("islandFirstFloat", false)
            put("param_island", paramIsland)
            put("animTextInfo", animTextInfo)
            put("actions", actionsArray)
        }

        val rootParam = JSONObject().apply {
            put("protocol", 1)
            put("scene", "recorder")
            put("title", title)
            put("content", content)
            put("timerType", timerType)
            put("timerWhen", timerWhen)
            put("timerSystemCurrent", now)
            put("updatable", true)
            put("enableFloat", false)
            put("showSmallIcon", false)
            put("param_v2", paramV2)
        }

        val rvBar = buildFocusRemoteViews(
            R.layout.focus_notification_bar,
            courseName,
            isPaused,
            elapsedMs,
            pauseIntent,
            stopIntent
        )
        val rvBarNight = buildFocusRemoteViews(
            R.layout.focus_notification_bar_night,
            courseName,
            isPaused,
            elapsedMs,
            pauseIntent,
            stopIntent
        )

        return Bundle().apply {
            putParcelable("miui.focus.rvBar", rvBar)
            putParcelable("miui.focus.rvBarNight", rvBarNight)
            putBundle("miui.focus.pics", picsBundle)
            putBundle("miui.focus.actions", actionsBundle)
            putString("miui.focus.param", rootParam.toString())
            putParcelable("miui.focus.pic_ticker", picIcon)
            putParcelable("miui.focus.pic_ticker_dark", picIcon)
            putStringArray(
                "miui.focus.ticker",
                arrayOf(if (isPaused) "Paused - $courseName" else "Recording - $courseName")
            )
            putInt("miui.focus.tickerAutoClose", 0)
        }
    }

    private fun buildFocusRemoteViews(
        layoutRes: Int,
        courseName: String,
        isPaused: Boolean,
        elapsedMs: Long,
        pauseIntent: PendingIntent,
        stopIntent: PendingIntent
    ): RemoteViews {
        val rv = RemoteViews(packageName, layoutRes)
        rv.setTextViewText(R.id.focus_title, courseName)
        rv.setTextViewText(R.id.focus_status, if (isPaused) "Paused" else "Recording")
        rv.setChronometer(
            R.id.focus_chronometer,
            SystemClock.elapsedRealtime() - elapsedMs,
            "%s",
            !isPaused
        )
        rv.setTextViewText(R.id.focus_btn_pause, if (isPaused) "Resume" else "Pause")
        rv.setOnClickPendingIntent(R.id.focus_btn_pause, pauseIntent)
        rv.setOnClickPendingIntent(R.id.focus_btn_stop, stopIntent)
        return rv
    }

    override fun onDestroy() {
        if (_state.value.isRecording) {
            stopInternal()
        }
        tickerJob?.cancel()
        checkpointJob?.cancel()
        scope.cancel()
        recorder?.release()
        recorder = null
        activeInstance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "class_recorder_live_v2"
        @Volatile
        private var activeInstance: RecordingService? = null
        @Volatile
        private var crashHandlerInstalled = false

        fun notificationIdFor(context: Context): Int =
            1001 + (context.packageName.hashCode() and 0x1FF)

        const val ACTION_START = "com.ryno.classrecorder.ACTION_START"
        const val ACTION_STOP = "com.ryno.classrecorder.ACTION_STOP"
        const val ACTION_DISCARD = "com.ryno.classrecorder.ACTION_DISCARD"
        const val ACTION_TOGGLE_PAUSE = "com.ryno.classrecorder.ACTION_TOGGLE_PAUSE"
        const val EXTRA_COURSE_JSON = "course_json"

        private val _state = MutableStateFlow(RecorderState())
        val state = _state.asStateFlow()

        private val _finishedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val finishedEvents = _finishedEvents.asSharedFlow()

        fun start(context: Context, courseJson: String = "{}") {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COURSE_JSON, courseJson)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun discard(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_DISCARD }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_TOGGLE_PAUSE }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
