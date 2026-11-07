package org.example.project.features.backup

import org.example.project.features.backup.data.service.BackupArchiveNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupArchiveNamesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a free name is used as is`() {
        val file = BackupArchiveNames.reserve(dir, "20260515_122452")

        assertEquals("pos_backup_20260515_122452.zip", file.name)
        assertTrue(file.exists(), "the name is reserved by creating the file")
    }

    @Test
    fun `backups in the same second get distinct names and none is overwritten`() {
        val first = BackupArchiveNames.reserve(dir, "20260515_122452").also { it.writeText("one") }
        val second = BackupArchiveNames.reserve(dir, "20260515_122452").also { it.writeText("two") }
        val third = BackupArchiveNames.reserve(dir, "20260515_122452").also { it.writeText("three") }

        assertEquals(
            listOf(
                "pos_backup_20260515_122452.zip",
                "pos_backup_20260515_122452_2.zip",
                "pos_backup_20260515_122452_3.zip",
            ),
            listOf(first, second, third).map {
                it.name
            },
        )
        assertEquals(listOf("one", "two", "three"), listOf(first, second, third).map { it.readText() })
    }

    @Test
    fun `every name keeps the shape the retention pruning and the copy target look for`() {
        repeat(3) { BackupArchiveNames.reserve(dir, "20260515_122452") }

        val names = dir.listFiles()!!.map { it.name }
        assertEquals(3, names.size)
        assertTrue(names.all { it.startsWith("pos_backup_") && it.endsWith(".zip") }, names.toString())
    }
}
