package org.example.project.features.tse.verification

import kotlinx.coroutines.test.runTest
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TseExportDirectory] resolves `<getDatevXmlExportPath()>/tse`, falling back to the relative
 * `exports/tse` when unconfigured (blank). [fakeConfig] answers only that one accessor via a
 * dynamic proxy — the interface has ~40 members, and implementing all of them by hand isn't
 * warranted for a resolver that reads exactly one. Mirrors the stub convention already used for
 * this interface elsewhere in the suite (see KassenbuchSignedEntryServiceTest).
 */
class TseExportDirectoryTest {
    private fun fakeConfig(exportPath: String): IShopConfigurationManager {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            IShopConfigurationManager::class.java.classLoader,
            arrayOf(IShopConfigurationManager::class.java),
        ) { _, method, _ ->
            when (method.name) {
                // No restore awaiting review: the export folder may be used.
                "getRestoredPathsPendingReview" -> emptyMap<Any, Any>()
                "getDatevXmlExportPath" -> exportPath
                else -> throw NotImplementedError(
                    "Stub: IShopConfigurationManager.${method.name} should not be called in TseExportDirectory tests",
                )
            }
        } as IShopConfigurationManager
    }

    @AfterEach
    fun tearDown() {
        // The blank-path case resolves the real relative fallback (matching production
        // behaviour), so clean it back up rather than leaving litter in the working tree.
        File("exports", "tse").deleteRecursively()
    }

    @Test
    fun `a DATEV path restored from another installation and not yet reviewed is refused, not created`(
        @TempDir dir: File,
    ) {
        val paused =
            Proxy.newProxyInstance(
                IShopConfigurationManager::class.java.classLoader,
                arrayOf(IShopConfigurationManager::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getRestoredPathsPendingReview" -> mapOf(RestoredPathSetting.DATEV_EXPORT_PATH to dir.path)
                    else -> throw NotImplementedError("Stub: ${method.name} must not be reached before the guard")
                }
            } as IShopConfigurationManager

        val refusal = assertFailsWith<PosException> { runTest { TseExportDirectory(paused).resolve() } }

        assertEquals(PosErrorCode.RESTORED_PATHS_NEED_REVIEW, refusal.code)
        assertFalse(File(dir, "tse").exists())
    }

    @Test
    fun `blank configured path falls back to the relative exports-tse directory`() {
        runTest {
            val resolved = TseExportDirectory(fakeConfig("")).resolve()
            assertEquals(File("exports", "tse").canonicalFile, resolved.canonicalFile)
            assertTrue(resolved.isDirectory)
        }
    }

    @Test
    fun `a configured path resolves to its tse subfolder and is created`(
        @TempDir dir: File,
    ) {
        runTest {
            val resolved = TseExportDirectory(fakeConfig(dir.path)).resolve()
            assertEquals(File(dir, "tse").canonicalFile, resolved.canonicalFile)
            assertTrue(resolved.isDirectory)
        }
    }
}
