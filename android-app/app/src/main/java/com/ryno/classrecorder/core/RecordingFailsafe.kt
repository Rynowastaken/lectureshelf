package com.ryno.classrecorder.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

internal data class RecordingCheckpoint(
    val id: String,
    val filePath: String,
    val elapsedMs: Long
)

/**
 * Multi-layer persistence and crash-recovery helpers so active recordings
 * are finalized and never lost if the service, UI, or process is interrupted.
 */
object RecordingFailsafe {
    private const val TAG = "RecordingFailsafe"
    private const val CHECKPOINT_FILE = "active_recording_checkpoint.properties"
    private const val PENDING_PREFIX = "pending_complete_"

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun encodeProperties(vararg pairs: Pair<String, String>): String {
        val props = Properties()
        for ((k, v) in pairs) {
            props.setProperty(k, v)
        }
        return StringWriter().use { sw ->
            props.store(sw, null)
            sw.toString()
        }
    }

    private fun decodeProperties(raw: String): Properties = Properties().apply {
        load(StringReader(raw))
    }

    internal fun saveCheckpoint(filesDir: File, id: String, filePath: String, elapsedMs: Long) {
        runCatching {
            val payload = encodeProperties(
                "id" to id,
                "filePath" to filePath,
                "elapsedMs" to elapsedMs.coerceAtLeast(0L).toString()
            )
            writeAtomically(File(filesDir, CHECKPOINT_FILE), payload)
        }
    }

    internal fun loadCheckpoint(filesDir: File): RecordingCheckpoint? = runCatching {
        val file = File(filesDir, CHECKPOINT_FILE)
        if (!file.exists()) return@runCatching null
        val props = decodeProperties(file.readText())
        val id = props.getProperty("id", "").trim()
        if (id.isEmpty()) return@runCatching null
        RecordingCheckpoint(
            id = id,
            filePath = props.getProperty("filePath", ""),
            elapsedMs = props.getProperty("elapsedMs", "0").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        )
    }.getOrNull()

    internal fun clearCheckpoint(filesDir: File, expectedId: String? = null) {
        runCatching {
            val file = File(filesDir, CHECKPOINT_FILE)
            if (!file.exists()) return@runCatching
            if (expectedId != null) {
                val current = loadCheckpoint(filesDir)
                if (current != null && current.id != expectedId) return@runCatching
            }
            file.delete()
        }
    }

    internal fun savePendingCompletion(filesDir: File, id: String, durationMs: Long) {
        runCatching {
            val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val payload = encodeProperties(
                "id" to id,
                "durationMs" to durationMs.coerceAtLeast(0L).toString()
            )
            writeAtomically(File(filesDir, "$PENDING_PREFIX$safeId.properties"), payload)
        }
    }

    fun probeAudioDurationMs(filePath: String): Long? {
        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun resolveRecoveredDurationMs(
        file: File,
        startedAtSec: Long,
        checkpointElapsedMs: Long?,
        probedDurationMs: Long?
    ): Long {
        if (probedDurationMs != null && probedDurationMs > 0L) return probedDurationMs
        if (checkpointElapsedMs != null && checkpointElapsedMs > 0L) return checkpointElapsedMs
        val fromMtime = file.lastModified() - (startedAtSec * 1000L)
        return fromMtime.coerceAtLeast(1000L)
    }

    /**
     * Marks a recording completed in RustCore with retries, falling back to a local
     * pending-completion sidecar if SQLite is temporarily unavailable.
     */
    fun completeWithRetry(context: Context, id: String, durationMs: Long): Boolean {
        val ctx = context.applicationContext
        AppRecordingBridge.ensureCoreInitialized(ctx)
        val safeDuration = durationMs.coerceAtLeast(0L)
        repeat(3) { attempt ->
            val ok = runCatching {
                JSONObject(RustCore.completeRecording(id, safeDuration)).optBoolean("ok", false)
            }.getOrDefault(false)
            if (ok) {
                clearCheckpoint(ctx.filesDir, id)
                return true
            }
            if (attempt < 2) {
                runCatching { Thread.sleep(100L * (attempt + 1)) }
            }
        }
        Log.w(TAG, "completeRecording failed after retries for $id; queuing sidecar")
        savePendingCompletion(ctx.filesDir, id, safeDuration)
        return false
    }

    /**
     * Replays any queued completions and rescues orphaned `status == "recording"`
     * entries left behind by a crash or OS kill. Returns the number of recordings recovered.
     */
    fun recoverOrphanedRecordings(context: Context, activeRecordingId: String? = null): Int {
        val ctx = context.applicationContext
        AppRecordingBridge.ensureCoreInitialized(ctx)
        var recovered = 0

        // 1. Replay pending completion sidecars
        ctx.filesDir.listFiles { _, name ->
            name.startsWith(PENDING_PREFIX) && name.endsWith(".properties")
        }?.forEach { sidecar ->
            val props = runCatching { decodeProperties(sidecar.readText()) }.getOrNull()
            val id = props?.getProperty("id", "").orEmpty()
            val durationMs = props?.getProperty("durationMs", "0")?.toLongOrNull() ?: 0L
            if (id.isNotEmpty() && id != activeRecordingId) {
                val ok = runCatching {
                    JSONObject(RustCore.completeRecording(id, durationMs)).optBoolean("ok", false)
                }.getOrDefault(false)
                if (ok) {
                    sidecar.delete()
                    recovered++
                }
            } else if (id.isEmpty()) {
                sidecar.delete()
            }
        }

        // 2. Recover any recordings stuck in "recording" status whose audio file exists
        val checkpoint = loadCheckpoint(ctx.filesDir)
        val listObj = runCatching { JSONObject(RustCore.listRecordings("")) }.getOrNull()
        if (listObj?.optBoolean("ok", false) == true) {
            val recordings = JsonModels.parseRecordings(listObj.optJSONArray("data"))
            for (rec in recordings) {
                if (rec.status != "recording" || rec.id == activeRecordingId) continue
                val file = File(rec.filePath)
                if (file.exists() && file.length() > 0L) {
                    val probed = probeAudioDurationMs(rec.filePath)
                    val cpMs = if (checkpoint?.id == rec.id) checkpoint.elapsedMs else null
                    val durationMs = resolveRecoveredDurationMs(file, rec.startedAt, cpMs, probed)
                    val ok = runCatching {
                        JSONObject(RustCore.completeRecording(rec.id, durationMs)).optBoolean("ok", false)
                    }.getOrDefault(false)
                    if (ok) {
                        clearCheckpoint(ctx.filesDir, rec.id)
                        recovered++
                    }
                }
            }
        }

        return recovered
    }
}
