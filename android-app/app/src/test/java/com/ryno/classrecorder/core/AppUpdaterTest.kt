package com.ryno.classrecorder.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(AppUpdater.isNewerVersion("v0.3.4", "0.3.3"))
        assertTrue(AppUpdater.isNewerVersion("v0.3.10", "0.3.3"))
        assertTrue(AppUpdater.isNewerVersion("v0.4.0", "0.3.9"))
        assertTrue(AppUpdater.isNewerVersion("1.0.0", "0.9.9"))
        assertTrue(AppUpdater.isNewerVersion("v0.3.4", "0.3.3-uitest"))

        assertFalse(AppUpdater.isNewerVersion("v0.3.3", "0.3.3"))
        assertFalse(AppUpdater.isNewerVersion("0.3.3", "0.3.3-uitest"))
        assertFalse(AppUpdater.isNewerVersion("v0.3.2", "0.3.3"))
        assertFalse(AppUpdater.isNewerVersion("v0.2.9", "0.3.0"))
    }
}
