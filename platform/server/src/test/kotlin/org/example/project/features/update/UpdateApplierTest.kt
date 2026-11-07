package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.features.update.data.service.UpdateApplier
import org.example.project.features.update.domain.models.UpdateMarker
import org.example.project.infrastructure.errors.PosException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The applier replaces the binary of a running fiscal system. Every test here therefore asserts
 * not only what it did but what it left alone: `native/` must survive untouched, and every abort
 * path must leave the old jar in place *and* refrain from exiting -- an applier that exits after
 * abandoning the swap restarts the server for nothing.
 */
class UpdateApplierTest {
    private val installDir: File = Files.createTempDirectory("applier-install").toFile()
    private val work: File = Files.createTempDirectory("applier-work").toFile()

    private val jar = File(installDir, "pos-server.jar")
    private val backupJar = File(installDir, "pos-server.jar.bak")
    private val wormApi = File(installDir, "native/WormAPI.jar")

    private val staged = File(work, "zenbon-pos-server-1.2.0.zip")
    private val dbBackup = File(work, "pre-update.dump")
    private val extracted = File(work, "extracted.jar")

    private val exits = mutableListOf<Int>()

    init {
        jar.writeText("OLD JAR")
        wormApi.parentFile.mkdirs()
        wormApi.writeText("TSE SDK")
        staged.writeText("staged archive")
        dbBackup.writeText("dump bytes")
        extracted.writeText("NEW JAR")
    }

    @AfterTest fun tearDown() {
        installDir.deleteRecursively()
        work.deleteRecursively()
    }

    private fun applier(
        createBackup: suspend () -> String = { dbBackup.absolutePath },
        extract: (File) -> File = { extracted },
    ) = UpdateApplier(
        installDir = installDir,
        createBackup = createBackup,
        extractServerJar = extract,
        exitProcess = { exits += it },
    )

    private fun marker() = File(installDir, UpdateApplier.MARKER)

    /** Asserts the destructive half never ran. */
    private fun assertNothingWasReplaced() {
        assertEquals("OLD JAR", jar.readText(), "an aborted apply must leave the running jar in place")
        assertFalse(exits.isNotEmpty(), "an aborted apply must not exit -- restarting the server would achieve nothing")
    }

    @Test fun `backs up the old jar before replacing it`() =
        runTest {
            applier().apply("1.2.0", staged)
            assertEquals("OLD JAR", backupJar.readText())
            assertEquals("NEW JAR", jar.readText())
        }

    @Test fun `writes a marker naming the target version and the database backup`() =
        runTest {
            applier().apply("1.2.0", staged)
            val written = Json.decodeFromString<UpdateMarker>(marker().readText())
            assertEquals("1.2.0", written.targetVersion)
            assertEquals(dbBackup.absolutePath, written.databaseBackupPath)
            assertTrue(written.startedAt.isNotBlank())
        }

    @Test fun `exits once with the update-applied code`() =
        runTest {
            applier().apply("1.2.0", staged)
            assertEquals(listOf(UpdateApplier.EXIT_UPDATE_APPLIED), exits)
            assertEquals(42, UpdateApplier.EXIT_UPDATE_APPLIED)
        }

    @Test fun `never touches native`() =
        runTest {
            applier().apply("1.2.0", staged)
            assertEquals(
                "TSE SDK",
                wormApi.readText(),
                "this is a jar-only update: a public zenbon-pos artifact ships WITHOUT WormAPI.jar, " +
                    "so replacing native/ from one would destroy a private install's TSE runtime",
            )
        }

    @Test fun `aborts when the database backup is empty`() =
        runTest {
            dbBackup.writeText("")
            assertFailsWith<PosException> { applier().apply("1.2.0", staged) }
            assertNothingWasReplaced()
            Unit
        }

    @Test fun `aborts when the database backup file is missing`() =
        runTest {
            assertFailsWith<PosException> {
                applier(createBackup = { File(work, "never-written.dump").absolutePath }).apply("1.2.0", staged)
            }
            assertNothingWasReplaced()
            Unit
        }

    @Test fun `aborts when the backup throws`() =
        runTest {
            assertFailsWith<IllegalStateException> {
                applier(createBackup = { error("pg_dump failed") }).apply("1.2.0", staged)
            }
            assertNothingWasReplaced()
            Unit
        }

    @Test fun `aborts when the extracted jar is empty`() =
        runTest {
            val empty = File(work, "empty.jar").also { it.writeBytes(ByteArray(0)) }
            assertFailsWith<PosException> { applier(extract = { empty }).apply("1.2.0", staged) }
            assertNothingWasReplaced()
            assertFalse(marker().exists(), "nothing may be written before the new jar is known to exist")
            Unit
        }

    @Test fun `aborts when the extractor names a file that does not exist`() =
        runTest {
            val missing = File(work, "never-written.jar")
            assertFailsWith<PosException> { applier(extract = { missing }).apply("1.2.0", staged) }
            assertNothingWasReplaced()
            assertFalse(marker().exists())
            Unit
        }

    // --- the bare jar -------------------------------------------------------------------------

    /**
     * A delta rebuild stages `pos-server-<version>.jar` directly, so there is nothing to unzip.
     * The swap, the backup and the exit code must be identical to the archive path -- the applier
     * is deliberately ignorant of how the update was transported.
     */
    @Test fun `applies a staged bare jar without unzipping`() =
        runTest {
            val bareJar = File(work, "pos-server-1.2.0.jar").also { it.writeText("NEW JAR") }

            applier(extract = UpdateApplier::serverJarFrom).apply("1.2.0", bareJar)

            assertEquals(listOf(UpdateApplier.EXIT_UPDATE_APPLIED), exits)
            assertEquals("NEW JAR", jar.readText())
            assertEquals("OLD JAR", backupJar.readText())
            assertEquals("TSE SDK", wormApi.readText())
        }

    @Test fun `serverJarFrom returns a bare jar untouched and still unzips an archive`() {
        val bareJar = File(work, "pos-server-1.2.0.jar").also { it.writeText("NEW JAR") }
        assertEquals(bareJar, UpdateApplier.serverJarFrom(bareJar))

        val zip = zipWith("pos-server.jar" to "NEW JAR")
        val fromZip = UpdateApplier.serverJarFrom(zip)
        assertEquals("NEW JAR", fromZip.readText())
        assertFalse(fromZip == zip, "the archive path must yield a file distinct from the archive")
    }

    // --- the production extractor -------------------------------------------------------------

    private fun zipWith(vararg entries: Pair<String, String>): File {
        val out = File(work, "zipped.zip")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        out.writeBytes(bytes.toByteArray())
        return out
    }

    @Test fun `extracts pos-server jar from the staged archive`() {
        val zip = zipWith("run.sh" to "#!/bin/bash", "pos-server.jar" to "NEW JAR", "VERSION" to "1.2.0")
        assertEquals("NEW JAR", UpdateApplier.extractServerJarFromZip(zip).readText())
    }

    /**
     * The entry is streamed to disk, never read into a `ByteArray` -- it is ~127 MB on a real
     * release. The destination therefore has to be chosen, not defaulted: beside the archive
     * (whose directory is already known to hold a release-sized file), named after it so two
     * concurrent versions cannot collide, and distinct from both the archive itself and any
     * `pos-server.jar` the installation directory holds.
     */
    @Test fun `streams the entry to a file beside the archive, colliding with nothing`() {
        val zip = zipWith("pos-server.jar" to "NEW JAR")

        val out = UpdateApplier.extractServerJarFromZip(zip)

        assertEquals(zip.parentFile, out.parentFile)
        assertFalse(out == zip, "the extracted jar must not overwrite the staged archive")
        assertFalse(out.name == "pos-server.jar", "it must not be able to land on an installed jar name")
        assertTrue(zip.readBytes().isNotEmpty(), "the staged archive must survive extraction")
    }

    @Test fun `refuses a staged archive that carries no server jar`() {
        val zip = zipWith("run.sh" to "#!/bin/bash", "native/WormAPI.jar" to "TSE SDK")
        val e = assertFailsWith<PosException> { UpdateApplier.extractServerJarFromZip(zip) }
        assertTrue(e.message!!.contains("pos-server.jar"))
        Unit
    }
}
