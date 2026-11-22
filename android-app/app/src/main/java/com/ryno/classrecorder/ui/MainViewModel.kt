package com.ryno.classrecorder.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ryno.classrecorder.core.AppDefaults
import com.ryno.classrecorder.core.AppUpdateInfo
import com.ryno.classrecorder.core.AppUpdater
import com.ryno.classrecorder.core.CourseSlot
import com.ryno.classrecorder.core.CurrentClassMatch
import com.ryno.classrecorder.core.ExportProgress
import com.ryno.classrecorder.core.ExportResult
import com.ryno.classrecorder.core.InstallLaunchResult
import com.ryno.classrecorder.core.JsonModels
import com.ryno.classrecorder.core.McpStatus
import com.ryno.classrecorder.core.PromotionGrant
import com.ryno.classrecorder.core.RecordingBridge
import com.ryno.classrecorder.core.RecordingExporter
import com.ryno.classrecorder.core.RecordingRecord
import com.ryno.classrecorder.core.RustCore
import com.ryno.classrecorder.core.UnifiedSchedule
import com.ryno.classrecorder.core.UpdateCheckResult
import com.ryno.classrecorder.service.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

enum class ExportFormat {
    ZIP,
    SINGLE_AUDIO
}

sealed interface ExportTarget {
    data object All : ExportTarget
    data class Course(val courseName: String) : ExportTarget
    data class Selected(val ids: Set<String>) : ExportTarget
}

data class AppUpdateState(
    val currentVersion: String = "0.0.0",
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val downloadProgress: Int? = null,
    val downloadedApkPath: String? = null,
    val upToDateChecked: Boolean = false
)

data class MainUiState(
    val schedule: UnifiedSchedule? = null,
    val currentClass: CurrentClassMatch? = null,
    val selectedCourseOverride: CourseSlot? = null,
    val recordings: List<RecordingRecord> = emptyList(),
    val mcpStatus: McpStatus = McpStatus(false, AppDefaults.MCP_PORT, emptyList()),
    val lanIp: String = "127.0.0.1",
    val scheduleSourceInput: String = AppDefaults.SCHEDULE_URL,
    val filenameTemplate: String = AppDefaults.FILENAME_TEMPLATE,
    val dateFormat: String = AppDefaults.DATE_FORMAT,
    val themeMode: DshThemeMode = DshThemeMode.SYSTEM,
    val accentId: String = "mist",
    val isSyncing: Boolean = false,
    val exportProgress: ExportProgress? = null,
    val updateState: AppUpdateState = AppUpdateState(),
    val statusMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application), RecordingBridge {

    private val _ui = MutableStateFlow(
        run {
            val (savedMode, savedAccent) = AppearancePreferences.load(application)
            val currentVersion = AppUpdater.currentVersionName(application)
            MainUiState(
                themeMode = savedMode,
                accentId = savedAccent,
                updateState = AppUpdateState(currentVersion = currentVersion)
            )
        }
    )
    val ui = _ui.asStateFlow()

    val recorderState = RecordingService.state

    private val lastSeenDataVersion = AtomicLong(-1L)
    @Volatile
    private var pendingExportTarget: ExportTarget = ExportTarget.All
    @Volatile
    private var pendingExportFormat: ExportFormat = ExportFormat.ZIP

    init {
        val ctx = getApplication<Application>()
        val recordingsDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Recordings")
        RustCore.initCore(ctx.filesDir.absolutePath, recordingsDir.absolutePath, this)

        if (PromotionGrant.isShizukuReady() && PromotionGrant.hasPermission(ctx)) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = PromotionGrant.request(ctx, enable = true)
                android.util.Log.i("ClassRecorderPromo", "promotion granted=$ok")
            }
        }

        viewModelScope.launch {
            RecordingService.finishedEvents.collect {
                refreshAll()
            }
        }

        runCatching { RustCore.startMcpServer(AppDefaults.MCP_PORT, "") }
        refreshAll()
        checkForUpdates(manual = false)
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val version = RustCore.getDataVersion()

            val schedObj = JSONObject(RustCore.getScheduleJson())
            val sched = if (schedObj.optBoolean("ok")) {
                JsonModels.parseSchedule(schedObj.optJSONObject("data"))
            } else null

            val curObj = JSONObject(RustCore.getCurrentClassJson())
            val cur = if (curObj.optBoolean("ok")) {
                JsonModels.parseCurrentClass(curObj.optJSONObject("data"))
            } else null

            val recObj = JSONObject(RustCore.listRecordings(""))
            val recs = if (recObj.optBoolean("ok")) {
                JsonModels.parseRecordings(recObj.optJSONArray("data"))
            } else emptyList()

            val mcpObj = JSONObject(RustCore.getMcpStatus())
            val mcp = if (mcpObj.optBoolean("ok")) {
                JsonModels.parseMcpStatus(mcpObj.optJSONObject("data"))
            } else McpStatus(false, AppDefaults.MCP_PORT, emptyList())

            val tmplObj = JSONObject(RustCore.getFilenameTemplate())
            val savedTmpl = if (tmplObj.optBoolean("ok")) {
                tmplObj.optJSONObject("data")?.optString("template", "").orEmpty()
            } else ""

            val fmtObj = JSONObject(RustCore.getDateFormat())
            val savedFmt = if (fmtObj.optBoolean("ok")) {
                fmtObj.optJSONObject("data")?.optString("dateFormat", "").orEmpty()
            } else ""

            val ip = detectLanIp()
            lastSeenDataVersion.set(version)

            _ui.update { prev ->
                prev.copy(
                    schedule = sched,
                    currentClass = cur,
                    recordings = recs,
                    mcpStatus = mcp,
                    lanIp = ip,
                    filenameTemplate = savedTmpl.ifBlank { AppDefaults.FILENAME_TEMPLATE },
                    dateFormat = savedFmt.ifBlank { AppDefaults.DATE_FORMAT },
                    scheduleSourceInput = sched?.sourceUrl ?: prev.scheduleSourceInput
                )
            }
        }
    }

    /**
     * Lightweight foreground-only tick invoked while the Activity is STARTED.
     * Skips SQLite recordings/schedule reads unless Rust's atomic `dataVersion` changed,
     * and avoids emitting duplicate states when nothing changed.
     */
    suspend fun refreshForegroundTick() = withContext(Dispatchers.IO) {
        val currentVersion = RustCore.getDataVersion()
        val versionChanged = currentVersion != lastSeenDataVersion.getAndSet(currentVersion)

        val curObj = JSONObject(RustCore.getCurrentClassJson())
        val cur = if (curObj.optBoolean("ok")) {
            JsonModels.parseCurrentClass(curObj.optJSONObject("data"))
        } else _ui.value.currentClass

        val mcpObj = JSONObject(RustCore.getMcpStatus())
        val mcp = if (mcpObj.optBoolean("ok")) {
            JsonModels.parseMcpStatus(mcpObj.optJSONObject("data"))
        } else _ui.value.mcpStatus

        val recs = if (versionChanged) {
            val recObj = JSONObject(RustCore.listRecordings(""))
            if (recObj.optBoolean("ok")) {
                JsonModels.parseRecordings(recObj.optJSONArray("data"))
            } else _ui.value.recordings
        } else {
            _ui.value.recordings
        }

        val sched = if (versionChanged) {
            val schedObj = JSONObject(RustCore.getScheduleJson())
            if (schedObj.optBoolean("ok")) {
                JsonModels.parseSchedule(schedObj.optJSONObject("data"))
            } else _ui.value.schedule
        } else {
            _ui.value.schedule
        }

        _ui.update { prev ->
            val next = prev.copy(
                schedule = sched,
                scheduleSourceInput = if (sched != prev.schedule) {
                    sched?.sourceUrl ?: prev.scheduleSourceInput
                } else prev.scheduleSourceInput,
                currentClass = cur,
                mcpStatus = mcp,
                recordings = recs
            )
            if (next == prev) prev else next
        }
    }

    fun refreshLanIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = detectLanIp()
            _ui.update { if (it.lanIp == ip) it else it.copy(lanIp = ip) }
        }
    }

    fun syncSchedule(sourceUrl: String) {
        updateSchedule("Schedule synced", sourceUrl) { RustCore.syncSchedule(sourceUrl) }
    }

    private val schedulePreferences by lazy {
        getApplication<Application>().getSharedPreferences("schedule_preferences", Context.MODE_PRIVATE)
    }

    fun usesPortalSignIn(sourceUrl: String): Boolean =
        schedulePreferences.getString("signed_in_source", null) == sourceUrl

    fun syncSignedInPortal(sourceUrl: String, json: String) {
        updateSchedule("Schedule synced", sourceUrl, signedIn = true) {
            RustCore.importScheduleJson(json, sourceUrl)
        }
    }

    fun importSchedule(uri: Uri) {
        updateSchedule("Schedule imported") {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the timetable file")
            val json = stream.bufferedReader().use { reader ->
                val content = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    if (content.length + count > 1_048_576) {
                        throw IOException("The timetable JSON is too large")
                    }
                    content.append(buffer, 0, count)
                }
                content.toString()
            }
            RustCore.importScheduleJson(json, "")
        }
    }

    private fun updateSchedule(
        successMessage: String,
        sourceUrl: String? = null,
        signedIn: Boolean = false,
        update: () -> String
    ) {
        if (_ui.value.isSyncing) return
        _ui.update {
            it.copy(isSyncing = true, statusMessage = null, scheduleSourceInput = sourceUrl ?: it.scheduleSourceInput)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { JSONObject(update()) }
            val response = result.getOrNull()
            if (response?.optBoolean("ok") == true) {
                if (signedIn) schedulePreferences.edit().putString("signed_in_source", sourceUrl).apply()
                refreshAll()
                _ui.update { it.copy(isSyncing = false, statusMessage = successMessage) }
            } else {
                val message = response?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: result.exceptionOrNull()?.message ?: "Could not update the timetable"
                _ui.update {
                    it.copy(isSyncing = false, statusMessage = message)
                }
            }
        }
    }

    fun selectCourseOverride(course: CourseSlot?) {
        _ui.update { it.copy(selectedCourseOverride = course) }
    }

    fun startRecording() {
        val course = _ui.value.selectedCourseOverride
        val json = if (course != null) {
            JSONObject().apply {
                put("courseName", course.name)
                put("teacher", course.teacher)
                put("room", course.room)
            }.toString()
        } else "{}"
        RecordingService.start(getApplication(), json)
    }

    fun startRecordingForCourse(course: CourseSlot, slot: String = "") {
        val json = JSONObject().apply {
            put("courseName", course.name)
            put("teacher", course.teacher)
            put("room", course.room)
            put("slot", slot)
        }.toString()
        _ui.update { it.copy(selectedCourseOverride = course) }
        RecordingService.start(getApplication(), json)
    }

    fun stopRecording() {
        RecordingService.stop(getApplication())
    }

    fun discardRecording() {
        RecordingService.discard(getApplication())
        _ui.update { it.copy(statusMessage = "Recording discarded") }
    }

    fun togglePause() {
        RecordingService.togglePause(getApplication())
    }

    fun updateNotes(id: String, notes: String, tags: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            RustCore.updateRecordingNotes(id, notes, JSONArray(tags).toString())
            refreshAll()
        }
    }

    fun prepareExport(target: ExportTarget, format: ExportFormat = ExportFormat.ZIP) {
        pendingExportTarget = target
        pendingExportFormat = format
    }

    fun exportSingleRecording(recording: RecordingRecord, destUri: Uri, onComplete: () -> Unit = {}) {
        if (_ui.value.exportProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    exportProgress = ExportProgress(
                        completedFiles = 0,
                        totalFiles = 1,
                        currentCourse = recording.courseName.ifBlank { "Uncategorized" }
                    )
                )
            }
            val result = RecordingExporter.exportSingleAudioToUri(
                context = getApplication(),
                destUri = destUri,
                recording = recording,
                onProgress = { progress ->
                    _ui.update { it.copy(exportProgress = progress) }
                }
            )
            val msg = formatExportResultMessage(result)
            _ui.update { it.copy(exportProgress = null, statusMessage = msg) }
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    fun exportRecordings(destUri: Uri, onComplete: () -> Unit = {}) {
        if (_ui.value.exportProgress != null) return
        val target = pendingExportTarget
        val format = pendingExportFormat
        viewModelScope.launch(Dispatchers.IO) {
            val allRecordings = _ui.value.recordings.ifEmpty {
                val recObj = JSONObject(RustCore.listRecordings(""))
                if (recObj.optBoolean("ok")) {
                    JsonModels.parseRecordings(recObj.optJSONArray("data"))
                } else emptyList()
            }
            val recordings = when (target) {
                ExportTarget.All -> allRecordings
                is ExportTarget.Course -> allRecordings.filter {
                    it.courseName.ifBlank { "Uncategorized" } == target.courseName
                }
                is ExportTarget.Selected -> allRecordings.filter { target.ids.contains(it.id) }
            }
            _ui.update { it.copy(exportProgress = ExportProgress(0, recordings.size)) }
            val result = if (format == ExportFormat.SINGLE_AUDIO && recordings.size == 1) {
                RecordingExporter.exportSingleAudioToUri(
                    context = getApplication(),
                    destUri = destUri,
                    recording = recordings.first(),
                    onProgress = { progress ->
                        _ui.update { it.copy(exportProgress = progress) }
                    }
                )
            } else {
                RecordingExporter.exportToUri(
                    context = getApplication(),
                    destUri = destUri,
                    recordings = recordings,
                    onProgress = { progress ->
                        _ui.update { it.copy(exportProgress = progress) }
                    }
                )
            }
            val msg = formatExportResultMessage(result)
            _ui.update { it.copy(exportProgress = null, statusMessage = msg) }
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    private fun formatExportResultMessage(result: ExportResult): String = when (result) {
        is ExportResult.Success -> {
            val singleName = result.exportedFileName
            if (!singleName.isNullOrBlank()) {
                "Exported $singleName"
            } else {
                val unit = if (result.exportedCount == 1) "recording" else "recordings"
                if (result.missingCount > 0) {
                    "Exported ${result.exportedCount} $unit to ZIP (${result.missingCount} missing on disk)"
                } else {
                    "Exported ${result.exportedCount} $unit to ZIP"
                }
            }
        }
        is ExportResult.Failure -> result.message
    }

    fun deleteRecording(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            RustCore.deleteRecording(id)
            refreshAll()
        }
    }

    fun toggleMcpServer(port: Int = AppDefaults.MCP_PORT, authToken: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (_ui.value.mcpStatus.running) {
                RustCore.stopMcpServer()
            } else {
                val res = JSONObject(RustCore.startMcpServer(port, authToken))
                if (!res.optBoolean("ok")) {
                    _ui.update { it.copy(statusMessage = res.optString("error", "MCP failed")) }
                }
            }
            val ip = detectLanIp()
            _ui.update { it.copy(lanIp = ip) }
            refreshForegroundTick()
        }
    }

    fun saveFilenameTemplate(template: String, dateFormat: String) {
        viewModelScope.launch(Dispatchers.IO) {
            RustCore.setFilenameTemplate(template)
            RustCore.setDateFormat(dateFormat)
            _ui.update {
                it.copy(
                    filenameTemplate = template.ifBlank { AppDefaults.FILENAME_TEMPLATE },
                    dateFormat = dateFormat.ifBlank { AppDefaults.DATE_FORMAT },
                    statusMessage = "Filename format saved"
                )
            }
        }
    }

    fun setThemeMode(mode: DshThemeMode) {
        AppearancePreferences.saveThemeMode(getApplication(), mode)
        _ui.update { it.copy(themeMode = mode) }
    }

    fun setAccentId(accentId: String) {
        val valid = AppearancePreferences.parseAccentId(accentId)
        AppearancePreferences.saveAccentId(getApplication(), valid)
        _ui.update { it.copy(accentId = valid) }
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_ui.value.updateState.isChecking || _ui.value.updateState.downloadProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentVersion = _ui.value.updateState.currentVersion
            _ui.update { it.copy(updateState = it.updateState.copy(isChecking = true)) }
            when (val res = AppUpdater.checkForUpdate(currentVersion)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _ui.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                isChecking = false,
                                availableUpdate = res.update,
                                upToDateChecked = false
                            ),
                            statusMessage = if (manual) "Update available: ${res.update.versionTag}" else it.statusMessage
                        )
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _ui.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                isChecking = false,
                                availableUpdate = null,
                                downloadedApkPath = null,
                                upToDateChecked = true
                            ),
                            statusMessage = if (manual) "You're on the latest version (v${currentVersion.removePrefix("v")})" else it.statusMessage
                        )
                    }
                }
                is UpdateCheckResult.Error -> {
                    _ui.update {
                        it.copy(
                            updateState = it.updateState.copy(isChecking = false),
                            statusMessage = if (manual) "Update check failed: ${res.message}" else it.statusMessage
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate(onNeedInstallPermission: (Intent) -> Unit) {
        val state = _ui.value.updateState
        if (state.downloadProgress != null) return
        val update = state.availableUpdate ?: return
        val ctx = getApplication<Application>()

        val cachedPath = state.downloadedApkPath
        if (!cachedPath.isNullOrBlank()) {
            val cachedFile = File(cachedPath)
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                launchApkInstaller(ctx, cachedFile, onNeedInstallPermission)
                return
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(updateState = it.updateState.copy(downloadProgress = 0)) }
            val result = AppUpdater.downloadApk(
                context = ctx,
                update = update,
                onProgress = { pct ->
                    _ui.update { it.copy(updateState = it.updateState.copy(downloadProgress = pct)) }
                }
            )
            result.fold(
                onSuccess = { apkFile ->
                    _ui.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                downloadProgress = null,
                                downloadedApkPath = apkFile.absolutePath
                            )
                        )
                    }
                    withContext(Dispatchers.Main.immediate) {
                        launchApkInstaller(ctx, apkFile, onNeedInstallPermission)
                    }
                },
                onFailure = { err ->
                    _ui.update {
                        it.copy(
                            updateState = it.updateState.copy(downloadProgress = null),
                            statusMessage = "Download failed: ${err.message ?: "network error"}"
                        )
                    }
                }
            )
        }
    }

    private fun launchApkInstaller(
        ctx: Application,
        apkFile: File,
        onNeedInstallPermission: (Intent) -> Unit
    ) {
        when (val res = AppUpdater.installApk(ctx, apkFile)) {
            InstallLaunchResult.Launched -> Unit
            is InstallLaunchResult.NeedsInstallPermission -> {
                showMessage("Allow installing updates from LectureShelf, then return")
                onNeedInstallPermission(res.settingsIntent)
            }
            is InstallLaunchResult.Error -> showMessage(res.message)
        }
    }

    fun showMessage(msg: String) {
        _ui.update { it.copy(statusMessage = msg) }
    }

    fun clearMessage() {
        _ui.update { it.copy(statusMessage = null) }
    }

    // Called from Rust MCP spawn_blocking threads via JNI
    override fun startRecordingFromAgent(courseJson: String): String {
        val ctx = getApplication<Application>()
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
        val active = RecordingService.state.value.activeRecording
            ?: return JSONObject().put("error", "No active recording").toString()
        RecordingService.stop(getApplication())
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

    private fun detectLanIp(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
