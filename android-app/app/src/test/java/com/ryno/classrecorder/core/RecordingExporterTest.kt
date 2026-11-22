package com.ryno.classrecorder.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class RecordingExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesValidStoredAudioAndDeflatedMetadataWithDedupedPaths() {
        val dirA = tempFolder.newFolder("a")
        val dirB = tempFolder.newFolder("b")
        val file1 = File(dirA, "lecture.m4a").apply { writeBytes(ByteArray(300_000) { (it * 31).toByte() }) }
        val file2 = File(dirB, "lecture.m4a").apply { writeBytes(ByteArray(12_345) { (it * 17).toByte() }) }

        val rec1 = RecordingRecord(
            id = "id-1",
            filePath = file1.absolutePath,
            courseName = "經濟學",
            teacher = "Prof. A",
            room = "3701",
            slot = "3",
            startedAt = 1_795_000_000L,
            durationMs = 60_000L,
            status = "completed",
            notes = "Midterm chapter 4",
            tags = listOf("midterm")
        )
        val rec2 = rec1.copy(id = "id-2", filePath = file2.absolutePath)

        val zipFile = tempFolder.newFile("out.zip")
        val progressUpdates = mutableListOf<ExportProgress>()

        zipFile.outputStream().use { os ->
            RecordingExporter.writeZipArchive(
                outputStream = os,
                items = listOf(rec1 to file1, rec2 to file2),
                onProgress = { progressUpdates.add(it) },
                metadataSerializer = { entries ->
                    entries.joinToString("\n") { (r, path) -> "${r.id}=$path" }
                }
            )
        }

        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(2, progressUpdates.last().completedFiles)
        assertEquals(2, progressUpdates.last().totalFiles)

        ZipFile(zipFile).use { zf ->
            val e1 = zf.getEntry("經濟學/lecture.m4a")
            val e2 = zf.getEntry("經濟學/lecture_2.m4a")
            val meta = zf.getEntry("metadata.json")

            assertEquals(ZipEntry.STORED, e1.method)
            assertEquals(ZipEntry.STORED, e2.method)
            assertEquals(ZipEntry.DEFLATED, meta.method)

            assertArrayEquals(file1.readBytes(), zf.getInputStream(e1).readBytes())
            assertArrayEquals(file2.readBytes(), zf.getInputStream(e2).readBytes())
            val metaText = zf.getInputStream(meta).readBytes().toString(Charsets.UTF_8)
            assertEquals("id-1=經濟學/lecture.m4a\nid-2=經濟學/lecture_2.m4a", metaText)
        }
    }

    @Test
    fun handlesZeroByteAudioFilesCleanly() {
        val emptyFile = tempFolder.newFile("empty.m4a")
        val rec = RecordingRecord(
            id = "id-empty",
            filePath = emptyFile.absolutePath,
            courseName = "",
            teacher = "",
            room = "",
            slot = "",
            startedAt = 0L,
            durationMs = 0L,
            status = "completed",
            notes = "",
            tags = emptyList()
        )
        val baos = ByteArrayOutputStream()
        RecordingExporter.writeZipArchive(
            outputStream = baos,
            items = listOf(rec to emptyFile),
            metadataSerializer = { "[]" }
        )
        val outFile = tempFolder.newFile("empty_out.zip").apply { writeBytes(baos.toByteArray()) }
        ZipFile(outFile).use { zf ->
            val entry = zf.getEntry("Uncategorized/empty.m4a")
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(0L, entry.size)
            assertEquals(0, zf.getInputStream(entry).readBytes().size)
        }
    }

    @Test
    fun writesSingleAudioFileDirectlyWithoutZipWrapper() {
        val rawM4a = ByteArray(128_000) { idx ->
            when (idx) {
                4 -> 'f'.code.toByte()
                5 -> 't'.code.toByte()
                6 -> 'y'.code.toByte()
                7 -> 'p'.code.toByte()
                else -> (idx * 13).toByte()
            }
        }
        val srcFile = tempFolder.newFile("大學之道11-18 - 1.m4a").apply { writeBytes(rawM4a) }
        val baos = ByteArrayOutputStream()
        RecordingExporter.writeSingleFile(baos, srcFile)

        val exported = baos.toByteArray()
        assertArrayEquals(rawM4a, exported)
        // Must NOT start with ZIP local file header PK\x03\x04
        assertTrue(!(exported[0] == 0x50.toByte() && exported[1] == 0x4B.toByte()))
    }
}
