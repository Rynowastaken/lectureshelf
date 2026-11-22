package com.ryno.classrecorder.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ryno.classrecorder.ui.screens.UiFormatters
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportProgress(
    val completedFiles: Int,
    val totalFiles: Int,
    val currentCourse: String = ""
)

sealed interface ExportResult {
    data class Success(
        val exportedCount: Int,
        val missingCount: Int = 0,
        val exportedFileName: String? = null
    ) : ExportResult
    data class Failure(val message: String) : ExportResult
}

/**
 * Exports either a single `.m4a` recording directly or bundles multiple `.m4a` recordings
 * with a `metadata.json` manifest into a ZIP archive.
 *
 * Audio files in ZIP archives are written with [ZipEntry.STORED] (pre-computed CRC32 + exact
 * length) over a 256 KB [BufferedOutputStream] so already-compressed AAC streams export at
 * full I/O speed without burning CPU on redundant DEFLATE passes.
 */
object RecordingExporter {

    private const val IO_BUFFER_BYTES = 256 * 1024

    fun exportSingleAudioToUri(
        context: Context,
        destUri: Uri,
        recording: RecordingRecord,
        onProgress: (ExportProgress) -> Unit = {}
    ): ExportResult {
        val resolver = context.contentResolver
        val file = File(recording.filePath)
        if (!file.exists() || !file.isFile) {
            runCatching { DocumentsContract.deleteDocument(resolver, destUri) }
            return ExportResult.Failure("Audio file not found on disk")
        }

        val os = runCatching { resolver.openOutputStream(destUri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(destUri) }.getOrNull()
            ?: return ExportResult.Failure("Failed to open destination file")

        val courseLabel = recording.courseName.ifBlank { "Uncategorized" }
        return try {
            onProgress(ExportProgress(completedFiles = 0, totalFiles = 1, currentCourse = courseLabel))
            os.use { stream ->
                writeSingleFile(stream, file)
            }
            onProgress(ExportProgress(completedFiles = 1, totalFiles = 1, currentCourse = courseLabel))
            ExportResult.Success(
                exportedCount = 1,
                missingCount = 0,
                exportedFileName = file.name
            )
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destUri) }
            ExportResult.Failure("Export failed: ${e.message ?: "unknown error"}")
        }
    }

    internal fun writeSingleFile(outputStream: OutputStream, file: File) {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        BufferedOutputStream(outputStream, IO_BUFFER_BYTES).use { out ->
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        }
    }

    fun exportToUri(
        context: Context,
        destUri: Uri,
        recordings: List<RecordingRecord>,
        onProgress: (ExportProgress) -> Unit = {}
    ): ExportResult {
        val resolver = context.contentResolver
        val existingPairs = recordings.mapNotNull { rec ->
            val f = File(rec.filePath)
            if (f.exists() && f.isFile) rec to f else null
        }
        val missingCount = recordings.size - existingPairs.size
        if (existingPairs.isEmpty()) {
            runCatching { DocumentsContract.deleteDocument(resolver, destUri) }
            return ExportResult.Failure("No audio files found on disk to export")
        }

        val os = runCatching { resolver.openOutputStream(destUri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(destUri) }.getOrNull()
            ?: return ExportResult.Failure("Failed to open destination file")

        return try {
            os.use { stream ->
                writeZipArchive(stream, existingPairs, onProgress)
            }
            ExportResult.Success(
                exportedCount = existingPairs.size,
                missingCount = missingCount
            )
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destUri) }
            ExportResult.Failure("Export failed: ${e.message ?: "unknown error"}")
        }
    }

    internal fun writeZipArchive(
        outputStream: OutputStream,
        items: List<Pair<RecordingRecord, File>>,
        onProgress: (ExportProgress) -> Unit = {},
        metadataSerializer: (List<Pair<RecordingRecord, String>>) -> String = ::serializeMetadataJson
    ) {
        val total = items.size
        val usedEntries = mutableSetOf<String>()
        val exportedEntries = ArrayList<Pair<RecordingRecord, String>>(total)
        val buffer = ByteArray(IO_BUFFER_BYTES)

        ZipOutputStream(BufferedOutputStream(outputStream, IO_BUFFER_BYTES)).use { zos ->
            items.forEachIndexed { idx, (rec, file) ->
                onProgress(
                    ExportProgress(
                        completedFiles = idx,
                        totalFiles = total,
                        currentCourse = rec.courseName.ifBlank { "Uncategorized" }
                    )
                )

                val entryName = UiFormatters.buildUniqueZipEntryPath(
                    courseName = rec.courseName,
                    fileName = file.name,
                    usedEntries = usedEntries
                )

                val (crcValue, byteCount) = computeCrcAndSize(file, buffer)
                val entry = ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = byteCount
                    compressedSize = byteCount
                    crc = crcValue
                    time = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                }

                zos.putNextEntry(entry)
                FileInputStream(file).use { input ->
                    var remaining = byteCount
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        zos.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                zos.closeEntry()

                exportedEntries.add(rec to entryName)
                onProgress(
                    ExportProgress(
                        completedFiles = idx + 1,
                        totalFiles = total,
                        currentCourse = rec.courseName.ifBlank { "Uncategorized" }
                    )
                )
            }

            val metadataBytes = metadataSerializer(exportedEntries).toByteArray(Charsets.UTF_8)
            val metaEntry = ZipEntry("metadata.json").apply {
                method = ZipEntry.DEFLATED
            }
            zos.putNextEntry(metaEntry)
            zos.write(metadataBytes)
            zos.closeEntry()
            zos.flush()
        }
    }

    private fun serializeMetadataJson(entries: List<Pair<RecordingRecord, String>>): String {
        val metaArr = JSONArray()
        for ((rec, archivePath) in entries) {
            metaArr.put(rec.toJsonObject().put("archivePath", archivePath))
        }
        return metaArr.toString(2)
    }

    private fun computeCrcAndSize(file: File, buffer: ByteArray): Pair<Long, Long> {
        val crc = CRC32()
        var total = 0L
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
                total += read
            }
        }
        return crc.value to total
    }
}
