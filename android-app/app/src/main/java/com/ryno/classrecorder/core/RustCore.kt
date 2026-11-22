package com.ryno.classrecorder.core

import androidx.annotation.Keep

@Keep
interface RecordingBridge {
    fun startRecordingFromAgent(courseJson: String): String
    fun stopRecordingFromAgent(): String
}

@Keep
object RustCore {
    init {
        System.loadLibrary("class_recorder_core")
    }

    external fun initCore(filesDir: String, recordingsDir: String, bridge: RecordingBridge): String
    external fun getDataVersion(): Long
    external fun syncSchedule(sourceUrl: String): String
    external fun importScheduleJson(raw: String, sourceUrl: String): String
    external fun getScheduleJson(): String
    external fun getCurrentClassJson(): String
    external fun prepareRecording(courseOverrideJson: String): String
    external fun completeRecording(id: String, durationMs: Long): String
    external fun listRecordings(courseFilter: String): String
    external fun updateRecordingNotes(id: String, notes: String, tagsJson: String): String
    external fun deleteRecording(id: String): String
    external fun startMcpServer(port: Int, authToken: String): String
    external fun stopMcpServer(): String
    external fun getMcpStatus(): String
    external fun setFilenameTemplate(template: String): String
    external fun getFilenameTemplate(): String
    external fun setDateFormat(dateFormat: String): String
    external fun getDateFormat(): String
}
