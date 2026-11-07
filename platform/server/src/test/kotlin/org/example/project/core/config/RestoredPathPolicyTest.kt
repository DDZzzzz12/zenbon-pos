package org.example.project.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.setup.database.tables.ConfigKey
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When is a restored path "another installation's"? The rule the whole post-restore pause hangs on:
 * absolute and outside this installation's app-data directory. Blank and relative paths never are.
 */
@DisplayName("RestoredPathPolicy — which restored paths belong to another installation")
class RestoredPathPolicyTest {
    private val appData: String = Files.createTempDirectory("restored-paths-appdata").toFile().absolutePath
    private val elsewhere: String = Files.createTempDirectory("restored-paths-elsewhere").toFile().absolutePath

    @Test
    fun `an absolute path outside the app-data directory is foreign`() {
        assertTrue(RestoredPathPolicy.isForeign(File(elsewhere, "datev").path, appData))
        assertTrue(RestoredPathPolicy.isForeign("/Users/dz/Zenbon POS/exports", appData))
    }

    @Test
    fun `a path inside the app-data directory, the directory itself, blank and relative are not`() {
        assertFalse(RestoredPathPolicy.isForeign(File(appData, "exports").path, appData))
        assertFalse(RestoredPathPolicy.isForeign(appData, appData))
        assertFalse(RestoredPathPolicy.isForeign("", appData))
        assertFalse(RestoredPathPolicy.isForeign("./documents", appData))
    }

    @Test
    fun `a sibling whose name merely starts like the app-data directory is foreign`() {
        // A string prefix test would wrongly treat "/home/x/POS-old" as inside "/home/x/POS".
        assertTrue(RestoredPathPolicy.isForeign("$appData-old/exports", appData))
    }

    @Test
    fun `a dot-dot escape out of the app-data directory is foreign`() {
        assertTrue(RestoredPathPolicy.isForeign("$appData/../elsewhere", appData))
    }

    @Test
    fun `a Windows path read on another OS is foreign`() {
        if (File.separatorChar == '\\') return // on Windows it is a native absolute path, covered above
        assertTrue(RestoredPathPolicy.isForeign("C:\\Users\\kasse\\Zenbon POS\\exports", appData))
    }

    @Test
    fun `configuredPaths reads the three path fields and skips blank and null ones`() {
        val settings =
            mapOf<String, JsonElement?>(
                ConfigKey.DATEV_XML_CONFIG.key to Json.parseToJsonElement("""{"enabled":true,"exportPath":"/a/datev"}"""),
                ConfigKey.BACKUP_CONFIG.key to Json.parseToJsonElement("""{"defaultBackupPath":null,"lastBackupAt":null}"""),
                ConfigKey.DOCUMENT_STORAGE_CONFIG.key to Json.parseToJsonElement("""{"path":""}"""),
            )
        assertEquals(
            mapOf(RestoredPathSetting.DATEV_EXPORT_PATH to "/a/datev"),
            RestoredPathPolicy.configuredPaths(settings),
        )
    }

    @Test
    fun `the review round-trips and a malformed row means nothing is pending`() {
        val pending = mapOf(RestoredPathSetting.BACKUP_PATH to "/x/backups", RestoredPathSetting.DATEV_EXPORT_PATH to "/x/d")
        assertEquals(pending, RestoredPathPolicy.decodeReview(RestoredPathPolicy.encodeReview(pending)))
        assertEquals(emptyMap(), RestoredPathPolicy.decodeReview(Json.parseToJsonElement("\"garbage\"")))
        assertEquals(emptyMap(), RestoredPathPolicy.decodeReview(Json.parseToJsonElement("""{"UNKNOWN":"/x"}""")))
        assertEquals(emptyMap(), RestoredPathPolicy.decodeReview(null))
    }

    @Test
    fun `defaults live under the app-data directory and the document path is stored blank`() {
        RestoredPathSetting.entries.forEach { setting ->
            assertFalse(RestoredPathPolicy.isForeign(RestoredPathPolicy.defaultPath(setting, appData), appData), "$setting")
        }
        assertEquals("", RestoredPathPolicy.storedDefault(RestoredPathSetting.DOCUMENT_STORAGE_PATH, appData))
        assertEquals(File(appData, "exports").path, RestoredPathPolicy.storedDefault(RestoredPathSetting.DATEV_EXPORT_PATH, appData))
        assertEquals(File(appData, "backups").path, RestoredPathPolicy.storedDefault(RestoredPathSetting.BACKUP_PATH, appData))
    }

    @Test
    fun `an alias of the app-data directory through a symlink is not foreign`() {
        val real = Files.createTempDirectory("restored-paths-real").toFile()
        val link = File(real.parentFile, "restored-paths-link-${System.nanoTime()}")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        try {
            // Configured through the link, app-data given as the real path — and the other way round,
            // for an existing folder and for one that does not exist yet.
            assertFalse(RestoredPathPolicy.isForeign(File(link, "exports").path, real.path))
            assertFalse(RestoredPathPolicy.isForeign(File(real, "exports").path, link.path))
            File(real, "backups").mkdirs()
            assertFalse(RestoredPathPolicy.isForeign(File(link, "backups").path, real.path))
            assertTrue(RestoredPathPolicy.isForeign(File(elsewhere, "x").path, link.path))
        } finally {
            Files.delete(link.toPath())
            real.deleteRecursively()
        }
    }
}
