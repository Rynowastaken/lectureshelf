package com.ryno.classrecorder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingFailsafeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun checkpointRoundTripAndClearRespectsExpectedId() {
        val dir = tempFolder.newFolder("files")
        RecordingFailsafe.saveCheckpoint(dir, "rec-1", "/tmp/a.m4a", 12_345L)
        val loaded = RecordingFailsafe.loadCheckpoint(dir)!!
        assertEquals("rec-1", loaded.id)
        assertEquals("/tmp/a.m4a", loaded.filePath)
        assertEquals(12_345L, loaded.elapsedMs)

        // Clearing with a mismatched ID must leave the newer checkpoint intact
        RecordingFailsafe.clearCheckpoint(dir, expectedId = "rec-other")
        assertEquals("rec-1", RecordingFailsafe.loadCheckpoint(dir)?.id)

        RecordingFailsafe.clearCheckpoint(dir, expectedId = "rec-1")
        assertNull(RecordingFailsafe.loadCheckpoint(dir))
    }

    @Test
    fun resolveRecoveredDurationPrefersProbedThenCheckpointThenFileMtime() {
        val file = tempFolder.newFile("rec.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val startedAtSec = (file.lastModified() / 1000L) - 42L

        assertEquals(
            18_500L,
            RecordingFailsafe.resolveRecoveredDurationMs(file, startedAtSec, 10_000L, 18_500L)
        )
        assertEquals(
            10_000L,
            RecordingFailsafe.resolveRecoveredDurationMs(file, startedAtSec, 10_000L, null)
        )
        val fallback = RecordingFailsafe.resolveRecoveredDurationMs(file, startedAtSec, null, null)
        assertTrue("Expected >= 40s fallback, got $fallback", fallback >= 40_000L)

        // Future/skewed clock still floors at 1000ms
        assertEquals(
            1000L,
            RecordingFailsafe.resolveRecoveredDurationMs(file, (file.lastModified() / 1000L) + 100L, null, null)
        )
    }

    @Test
    fun savePendingCompletionWritesSidecarFile() {
        val dir = tempFolder.newFolder("files")
        RecordingFailsafe.savePendingCompletion(dir, "rec/123", 99_000L)
        val sidecar = File(dir, "pending_complete_rec_123.properties")
        assertTrue(sidecar.exists())
        assertTrue(sidecar.readText().contains("rec/123"))
        assertTrue(sidecar.readText().contains("99000"))
    }
}
