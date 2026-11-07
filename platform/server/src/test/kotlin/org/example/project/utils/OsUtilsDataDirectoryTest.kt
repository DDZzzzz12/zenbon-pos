package org.example.project.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OsUtilsDataDirectoryTest {
    @Test
    @DisplayName("override wins and a trailing separator is dropped")
    fun overrideWins() {
        assertEquals("/var/lib/zenbon", OsUtils.resolveAppDataDirectory("/var/lib/zenbon/", "/home/pos", "Zenbon POS"))
    }

    @Test
    @DisplayName("blank override falls back to the home folder")
    fun blankOverrideFallsBack() {
        assertEquals("/home/pos/Zenbon POS", OsUtils.resolveAppDataDirectory("  ", "/home/pos", "Zenbon POS"))
        assertEquals("/home/pos/Zenbon POS", OsUtils.resolveAppDataDirectory(null, "/home/pos", "Zenbon POS"))
    }

    @Test
    @DisplayName("log directory uses LOG_DIR when set")
    fun logDirOverride() {
        assertEquals("/var/log/zenbon", OsUtils.resolveLogDirectory("/var/log/zenbon", "/x"))
    }

    @Test
    @DisplayName("log directory default is unchanged")
    fun logDirDefault() {
        assertEquals("/home/pos/Zenbon POS/.data/log", OsUtils.resolveLogDirectory(null, "/home/pos/Zenbon POS"))
        assertEquals("/home/pos/Zenbon POS/.data/log", OsUtils.resolveLogDirectory("", "/home/pos/Zenbon POS"))
    }
}
