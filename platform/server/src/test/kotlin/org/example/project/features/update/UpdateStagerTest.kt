package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import org.example.project.features.update.data.service.UpdateStager
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateStagerTest {
    private val dir: File = Files.createTempDirectory("stager").toFile()

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private val payload = "the new server".toByteArray()

    // verify with: printf 'the new server' | shasum -a 256
    private val payloadSha = "739edf3915918532d82b46109db70de407c381147a74856a8607e245dd9683c0"

    private fun sums(
        sha: String = payloadSha,
        name: String = "zenbon-pos-server-v1.2.0.zip",
    ) = "$sha  $name\n0000000000000000000000000000000000000000000000000000000000000000  zenbon-pos-1.2.0.apk\n"

    @Test fun `stages a payload whose checksum matches`() =
        runTest {
            val staged = UpdateStager(dir).stage("1.2.0", payload, sums())
            assertTrue(staged.exists())
            assertTrue(payload.contentEquals(staged.readBytes()))
        }

    @Test fun `refuses a payload whose checksum does not match`() =
        runTest {
            val e =
                assertFailsWith<PosException> {
                    UpdateStager(dir).stage("1.2.0", payload, sums(sha = "0".repeat(64)))
                }
            assertTrue(e.message!!.contains("checksum", ignoreCase = true))
            assertEquals(0, dir.listFiles()!!.size, "a rejected payload must not be left on disk")
            Unit
        }

    @Test fun `refuses when the checksum file does not mention the server archive`() =
        runTest {
            assertFailsWith<PosException> {
                UpdateStager(dir).stage("1.2.0", payload, "deadbeef  something-else.txt\n")
            }
            assertEquals(0, dir.listFiles()!!.size)
            Unit
        }

    @Test fun `refuses an empty checksum body`() =
        runTest {
            assertFailsWith<PosException> { UpdateStager(dir).stage("1.2.0", payload, "") }
            Unit
        }

    @Test fun `pruning removes stagings for other versions only`() =
        runTest {
            val sut = UpdateStager(dir)
            File(dir, "zenbon-pos-server-1.1.9.zip").writeText("old")
            val keep = sut.stage("1.2.0", payload, sums())
            sut.pruneExcept(keep)
            assertTrue(keep.exists())
            assertFalse(File(dir, "zenbon-pos-server-1.1.9.zip").exists())
        }

    /**
     * A delta stages `pos-server-<v>.jar`, not `zenbon-pos-server-<v>.zip`.
     *
     * Pruning by version derives the zip name, so the sweep straight after a delta staging deleted
     * the artifact that very run had just verified -- the update reported success and left nothing
     * to install. Pruning keeps the file it was handed instead.
     */
    @Test fun `pruning keeps a staged bare jar`() =
        runTest {
            val sut = UpdateStager(dir)
            val rebuilt = File(dir, "rebuilt.jar").apply { writeBytes(payload) }
            File(dir, "zenbon-pos-server-1.1.9.zip").writeText("old")
            val keep = sut.stageJar("1.2.0", rebuilt, payloadSha, "$payloadSha  pos-server-v1.2.0.jar\n")

            sut.pruneExcept(keep)

            assertEquals(listOf("pos-server-1.2.0.jar"), dir.listFiles()!!.map { it.name })
        }

    /**
     * The APK line must never be selectable. It carries the same version and the same
     * `zenbon-pos` prefix, so a rule matching on "contains the version" alone would pick it --
     * and here it is listed *first*, so a `firstOrNull` over a loose predicate would verify the
     * server bytes against the APK's digest and reject a perfectly good download.
     */
    @Test fun `never selects the apk line even when it is listed first and the server line is absent`() =
        runTest {
            val apkOnly = "$payloadSha  zenbon-pos-1.2.0.apk\n"
            assertFailsWith<PosException> { UpdateStager(dir).stage("1.2.0", payload, apkOnly) }
            assertEquals(0, dir.listFiles()!!.size)
            Unit
        }

    /**
     * With the APK first *and* carrying the matching digest, a loose match would succeed for the
     * wrong reason. The server line here holds a different digest, so selecting correctly means
     * the stage must fail.
     */
    @Test fun `checks against the server line even when the apk line precedes it`() =
        runTest {
            val body =
                "$payloadSha  zenbon-pos-1.2.0.apk\n" +
                    "${"0".repeat(64)}  zenbon-pos-server-v1.2.0.zip\n"
            val e = assertFailsWith<PosException> { UpdateStager(dir).stage("1.2.0", payload, body) }
            assertTrue(e.message!!.contains("checksum", ignoreCase = true))
            assertEquals(0, dir.listFiles()!!.size)
            Unit
        }

    @Test fun `ignores blank lines and tolerates the binary-mode star separator`() =
        runTest {
            val body = "\n$payloadSha *zenbon-pos-server-v1.2.0.zip\n\n"
            val staged = UpdateStager(dir).stage("1.2.0", payload, body)
            assertTrue(staged.exists())
        }

    /**
     * The exact bytes `release.yml` publishes. Captured verbatim from a real
     * `sha256sum -- *` run over a release directory holding the three artifact names CI
     * produces (`shasum -a 256` locally emits byte-identical output), so a change to either
     * side -- the workflow's format or this parser -- fails here rather than silently
     * leaving the update channel unable to stage anything.
     */
    @Test fun `parses a verbatim sha256sum output over the real release artifact set`() =
        runTest {
            val body =
                "739edf3915918532d82b46109db70de407c381147a74856a8607e245dd9683c0  zenbon-pos-server-v1.2.0.zip\n" +
                    "9379cfb95416438572c33e2c2e03f1fdbdb10e4668cfbdb7bbd0e3049463ac33  zenbon-pos-v1.2.0.apk\n" +
                    "86e3971b27cfdd073d838d8565c37230cf8c0e65cd7feafa3801265b605e21eb  zenbon-pos-v1.2.0.deb\n"
            val staged = UpdateStager(dir).stage("1.2.0", payload, body)
            assertTrue(staged.exists())
            assertEquals("zenbon-pos-server-1.2.0.zip", staged.name)
            assertTrue(payload.contentEquals(staged.readBytes()))
        }

    @Test fun `refuses a checksum line whose digest is not 64 hex characters`() =
        runTest {
            assertFailsWith<PosException> {
                UpdateStager(dir).stage("1.2.0", payload, "nothex  zenbon-pos-server-v1.2.0.zip\n")
            }
            assertEquals(0, dir.listFiles()!!.size)
            Unit
        }

    // --- the bare jar -------------------------------------------------------------------------

    private fun jarSums(
        sha: String = payloadSha,
        name: String = "pos-server-v1.2.0.jar",
    ) = "$sha  $name\n0000000000000000000000000000000000000000000000000000000000000000  zenbon-pos-server-v1.2.0.zip\n"

    @Test fun `accepts a rebuilt jar whose digest matches the jar entry`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }

            val staged = UpdateStager(dir).stageJar("1.2.0", rebuilt, payloadSha, jarSums())

            assertTrue(staged.exists())
            assertEquals("pos-server-1.2.0.jar", staged.name)
            assertTrue(payload.contentEquals(staged.readBytes()))
        }

    @Test fun `refuses a rebuilt jar whose digest does not match`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }

            val e =
                assertFailsWith<PosException> {
                    UpdateStager(dir).stageJar("1.2.0", rebuilt, "0".repeat(64), jarSums())
                }

            assertTrue(e.message!!.contains("checksum", ignoreCase = true))
            assertFalse(File(dir, "pos-server-1.2.0.jar").exists())
        }

    @Test fun `refuses when the checksum file names no jar`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }

            val e =
                assertFailsWith<PosException> {
                    UpdateStager(dir).stageJar("1.2.0", rebuilt, payloadSha, "$payloadSha  something-else.zip\n")
                }

            assertTrue(e.message!!.contains("no server jar"))
            Unit
        }

    @Test fun `refuses when the checksum file names two jars`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }
            val two = "$payloadSha  pos-server-v1.2.0.jar\n$payloadSha  pos-server-v1.3.0.jar\n"

            val e =
                assertFailsWith<PosException> {
                    UpdateStager(dir).stageJar("1.2.0", rebuilt, payloadSha, two)
                }

            assertTrue(e.message!!.contains("2 server jars"))
            Unit
        }

    /**
     * A rejected jar must leave the staging directory exactly as it found it -- the invariant the
     * whole class exists for. Here the directory already holds an unrelated staging, so "nothing
     * changed" is a stronger claim than "the directory is empty".
     */
    @Test fun `a refused jar leaves the staging directory untouched`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }
            File(dir, "zenbon-pos-server-1.1.9.zip").writeText("old")
            val before = dir.listFiles()!!.map { it.name }.toSet()

            assertFailsWith<PosException> {
                UpdateStager(dir).stageJar("1.2.0", rebuilt, "0".repeat(64), jarSums())
            }

            assertEquals(before, dir.listFiles()!!.map { it.name }.toSet())
            Unit
        }

    /**
     * The whole published set at once. `pos-server-<V>.jar.cdir.gz` sits beside
     * `pos-server-<V>.jar`, so a `contains(".jar")` spelling of the predicate matches **two**
     * lines and turns a correct download into a refusal; only an end-anchored `endsWith(".jar")`
     * picks one. A trimmed-down body would not catch that, which is why every name CI publishes
     * is listed here.
     */
    @Test fun `selects the jar line out of the whole published artifact set`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }
            val body =
                "0000000000000000000000000000000000000000000000000000000000000000  zenbon-pos-server-v1.2.0.zip\n" +
                    "1111111111111111111111111111111111111111111111111111111111111111  zenbon-pos-v1.2.0.apk\n" +
                    "2222222222222222222222222222222222222222222222222222222222222222  zenbon-pos-v1.2.0.deb\n" +
                    "$payloadSha  pos-server-v1.2.0.jar\n" +
                    "3333333333333333333333333333333333333333333333333333333333333333  pos-server-v1.2.0.jar.cdir.gz\n" +
                    "4444444444444444444444444444444444444444444444444444444444444444  SHA256SUMS\n"

            val staged = UpdateStager(dir).stageJar("1.2.0", rebuilt, payloadSha, body)

            assertEquals("pos-server-1.2.0.jar", staged.name)
            assertTrue(payload.contentEquals(staged.readBytes()))
        }

    /** The same body must still select the zip line for the full-archive path. */
    @Test fun `selects the zip line out of the whole published artifact set`() =
        runTest {
            val body =
                "$payloadSha  zenbon-pos-server-v1.2.0.zip\n" +
                    "1111111111111111111111111111111111111111111111111111111111111111  zenbon-pos-v1.2.0.apk\n" +
                    "2222222222222222222222222222222222222222222222222222222222222222  zenbon-pos-v1.2.0.deb\n" +
                    "3333333333333333333333333333333333333333333333333333333333333333  pos-server-v1.2.0.jar\n" +
                    "4444444444444444444444444444444444444444444444444444444444444444  pos-server-v1.2.0.jar.cdir.gz\n" +
                    "5555555555555555555555555555555555555555555555555555555555555555  SHA256SUMS\n"

            val staged = UpdateStager(dir).stage("1.2.0", payload, body)

            assertEquals("zenbon-pos-server-1.2.0.zip", staged.name)
        }

    /**
     * Unverified work happens in a subdirectory, never in the staging directory itself.
     *
     * The class contract is that a file present in the staging directory matched its published
     * digest at the moment it was written. A rebuild writes ~114 MB long before any digest is
     * compared, so it must not be written there: a power cut mid-rebuild — the exact scenario a POS
     * host is built around — would otherwise leave an unverified artifact in a directory whose
     * contract says the opposite.
     */
    @Test fun `the work directory is inside the staging directory but is not the staging directory`() {
        val stager = UpdateStager(dir)

        assertEquals(dir.canonicalFile, stager.workDir.parentFile.canonicalFile)
        assertTrue(
            stager.workDir.canonicalFile != dir.canonicalFile,
            "unverified rebuilds must not be written into the staging directory itself",
        )
    }

    /** And the sweep still reaches it, so an abandoned rebuild cannot leak ~114 MB. */
    @Test fun `pruneExcept deletes the work directory and keeps the staged artifact`() =
        runTest {
            val stager = UpdateStager(dir)
            val staged = stager.stage("1.2.0", payload, sums())
            stager.workDir.mkdirs()
            File(stager.workDir, "rebuilt-1.2.0.jar.part").writeText("half a jar")

            stager.pruneExcept(staged)

            assertFalse(stager.workDir.exists(), "the work directory must be swept like any other entry")
            assertTrue(staged.isFile, "the verified artifact of this very run must survive the sweep")
        }

    /**
     * A successful [UpdateStager.stageJar] CONSUMES its input.
     *
     * Source and target sit in the same directory tree, so copying was ~114 MB of pointless I/O and
     * a 2x transient disk high-water mark, on the very path whose purpose is to avoid moving a jar
     * around. A move leaves nothing behind.
     */
    @Test fun `stageJar moves the rebuilt jar rather than copying it`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }

            val staged = UpdateStager(dir).stageJar("1.2.0", rebuilt, payloadSha, jarSums())

            assertFalse(rebuilt.exists(), "stageJar must consume its input, not copy it")
            assertTrue(payload.contentEquals(staged.readBytes()))
        }

    /** On the failure path the input is untouched — which is what both call sites clean up. */
    @Test fun `a refused stageJar leaves the rebuilt jar where it was`() =
        runTest {
            val rebuilt = File(dir, "rebuilt.jar").also { it.writeBytes(payload) }

            assertFailsWith<PosException> {
                UpdateStager(dir).stageJar("1.2.0", rebuilt, "0".repeat(64), jarSums())
            }

            assertTrue(rebuilt.isFile, "a rejected rebuild must still be there for the caller to delete")
            Unit
        }

    @Test fun `reads the apk line and ignores the server lines`() {
        val apkSha = "a".repeat(64)
        val body = "${"b".repeat(64)}  zenbon-pos-server-v1.2.0.zip\n$apkSha  zenbon-pos-v1.2.0.apk\n"
        assertEquals(apkSha, UpdateStager(dir).expectedApkDigest("1.2.0", body))
    }

    @Test fun `refuses a checksum file with no apk line`() {
        assertFailsWith<PosException> {
            UpdateStager(dir).expectedApkDigest("1.2.0", "${"b".repeat(64)}  zenbon-pos-server-v1.2.0.zip\n")
        }
    }

    @Test fun `refuses a checksum file naming two apks`() {
        val body = "${"a".repeat(64)}  zenbon-pos-v1.2.0.apk\n${"c".repeat(64)}  other.apk\n"
        assertFailsWith<PosException> { UpdateStager(dir).expectedApkDigest("1.2.0", body) }
    }
}
