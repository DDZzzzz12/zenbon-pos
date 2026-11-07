package org.example.project.core.config

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataDirectoryRebaseTest {
    private val old = "/home/pos/Zenbon POS"
    private val new = "/var/lib/zenbon"

    @Test
    @DisplayName("a path inside the old base is moved")
    fun inside() {
        assertEquals("/var/lib/zenbon/backups", DataDirectoryRebase.rebase("/home/pos/Zenbon POS/backups", old, new))
        assertEquals(new, DataDirectoryRebase.rebase(old, old, new))
    }

    @Test
    @DisplayName("a path outside the old base is untouched")
    fun outside() {
        assertEquals("/media/pos/ZEN_BACKUP", DataDirectoryRebase.rebase("/media/pos/ZEN_BACKUP", old, new))
    }

    @Test
    @DisplayName("a sibling with the same prefix is untouched")
    fun sibling() {
        assertEquals("/home/pos/Zenbon POS2/x", DataDirectoryRebase.rebase("/home/pos/Zenbon POS2/x", old, new))
    }

    @Test
    @DisplayName("blank and relative paths are untouched")
    fun blankRelative() {
        assertEquals("", DataDirectoryRebase.rebase("", old, new))
        assertEquals("backups", DataDirectoryRebase.rebase("backups", old, new))
    }

    @Test
    @DisplayName("a dot-dot path escaping the old base is not moved")
    fun dotDot() {
        assertEquals("/home/pos/Zenbon POS/../other", DataDirectoryRebase.rebase("/home/pos/Zenbon POS/../other", old, new))
    }

    private class Store(
        initial: Map<RestoredPathSetting, String>,
    ) {
        val values = initial.toMutableMap()
        var writes = 0

        fun step(base: File) =
            DataDirectoryRebaseStep(
                readPath = { values[it] },
                writePath = { s, v ->
                    values[s] = v
                    writes++
                },
                appDataDirectory = { base.path },
            )
    }

    private fun marker(
        base: File,
        oldBase: String,
    ) {
        val m = File(base, DataDirectoryRebase.MARKER)
        m.parentFile.mkdirs()
        m.writeText(oldBase + "\n")
    }

    @Test
    @DisplayName("marker present rebases the three paths and deletes the marker; second run writes nothing")
    fun stepRebases(
        @TempDir base: File,
    ) {
        marker(base, old)
        val store =
            Store(
                mapOf(
                    RestoredPathSetting.DOCUMENT_STORAGE_PATH to "$old/documents",
                    RestoredPathSetting.BACKUP_PATH to "/media/pos/ZEN_BACKUP",
                    RestoredPathSetting.DATEV_EXPORT_PATH to "$old/exports",
                ),
            )
        val step = store.step(base)
        assertEquals(2, runBlocking { step.runIfMarked() })
        assertEquals("${base.path}/documents", store.values[RestoredPathSetting.DOCUMENT_STORAGE_PATH])
        assertEquals("/media/pos/ZEN_BACKUP", store.values[RestoredPathSetting.BACKUP_PATH])
        assertEquals("${base.path}/exports", store.values[RestoredPathSetting.DATEV_EXPORT_PATH])
        assertFalse(File(base, DataDirectoryRebase.MARKER).exists())
        assertEquals(0, runBlocking { step.runIfMarked() })
        assertEquals(2, store.writes)
    }

    @Test
    @DisplayName("marker absent writes nothing")
    fun noMarker(
        @TempDir base: File,
    ) {
        val store = Store(mapOf(RestoredPathSetting.BACKUP_PATH to "$old/backups"))
        assertEquals(0, runBlocking { store.step(base).runIfMarked() })
        assertEquals(0, store.writes)
    }

    @Test
    @DisplayName("a failing write keeps the marker for the next start")
    fun failedWriteKeepsMarker(
        @TempDir base: File,
    ) {
        marker(base, old)
        val step =
            DataDirectoryRebaseStep(
                readPath = { "$old/x" },
                writePath = { _, _ -> error("db down") },
                appDataDirectory = { base.path },
            )
        runCatching { runBlocking { step.runIfMarked() } }
        assertTrue(File(base, DataDirectoryRebase.MARKER).exists())
    }
}
