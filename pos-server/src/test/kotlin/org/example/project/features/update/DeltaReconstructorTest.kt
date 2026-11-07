package org.example.project.features.update

import kotlinx.coroutines.test.runTest
import org.example.project.features.update.data.service.DeltaPlanner
import org.example.project.features.update.data.service.DeltaReconstructor
import org.example.project.features.update.data.service.JarStructure
import org.example.project.features.update.domain.models.ByteRange
import org.junit.jupiter.api.Tag
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeltaReconstructorTest {
    private val dir: File = Files.createTempDirectory("reconstruct").toFile()

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (stream.read(buffer) >= 0) Unit
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Stands in for HTTP: serves ranges straight out of the target file. */
    private fun serve(target: File): suspend (ByteRange) -> ByteArray =
        { range ->
            RandomAccessFile(target, "r").use { file ->
                val bytes = ByteArray(range.length.toInt())
                file.seek(range.start)
                file.readFully(bytes)
                bytes
            }
        }

    private fun rebuild(
        local: File,
        target: File,
        out: File,
        fetch: suspend (ByteRange) -> ByteArray = serve(target),
    ): suspend () -> String {
        val targetStructure = JarStructure.parse(JarStructure.readCentralDirectory(target))
        val plan = DeltaPlanner().plan(JarStructure.of(local), targetStructure)
        return { DeltaReconstructor(fetch).reconstruct(local, targetStructure, plan, out) }
    }

    @Test fun `rebuilds a changed jar byte for byte from the installed one`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val changed =
                JarFixtures.THREE_ENTRIES.map {
                    if (it.first == "org/example/B.class") it.first to "class B CHANGED" else it
                }
            val target = JarFixtures.writeJar(File(dir, "target.jar"), changed)
            val out = File(dir, "rebuilt.jar")

            val digest = rebuild(local, target, out)()

            assertEquals(target.length(), out.length())
            assertTrue(target.readBytes().contentEquals(out.readBytes()))
            assertEquals(sha256(target), digest)
        }

    /**
     * Two `META-INF/LICENSE` entries with the same body, so their blocks share an identity.
     *
     * This is the pairing test with teeth. `rebuilds a jar carrying duplicate entry names` uses a
     * fixture whose two duplicates have different bodies and therefore different identities, which
     * a lookup keyed by identity resolves correctly by accident. Here the identities are equal and
     * only the bytes differ, so anything but the planner's positional pairing writes the wrong
     * block.
     */
    @Test fun `rebuilds two same-name entries whose blocks share an identity`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.WITH_SAME_CONTENT_DUPLICATES)
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    JarFixtures.WITH_SAME_CONTENT_DUPLICATES + ("org/example/C.class" to "new class"),
                )
            val out = File(dir, "rebuilt.jar")
            val duplicates = JarStructure.of(local).blocks.filter { it.name == "META-INF/LICENSE" }
            assertEquals(2, duplicates.size, "the fixture must hold two entries of that name")
            assertEquals(
                duplicates[0].identity,
                duplicates[1].identity,
                "the fixture is toothless unless the two blocks are indistinguishable by identity",
            )

            rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
        }

    @Test fun `rebuilds a jar carrying duplicate entry names`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.WITH_DUPLICATES)
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    JarFixtures.WITH_DUPLICATES + ("org/example/C.class" to "new class"),
                )
            val out = File(dir, "rebuilt.jar")

            rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
        }

    @Test fun `rebuilds correctly when nothing can be reused`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val target = JarFixtures.writeJar(File(dir, "target.jar"), listOf("only/One.class" to "unrelated"))
            val out = File(dir, "rebuilt.jar")

            rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
        }

    @Test fun `rebuilds a zip64 jar`() =
        runTest {
            val local = JarFixtures.writeZip64Jar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val target =
                JarFixtures.writeZip64Jar(
                    File(dir, "target.jar"),
                    JarFixtures.THREE_ENTRIES + ("org/example/C.class" to "class C body"),
                )
            val out = File(dir, "rebuilt.jar")

            val digest = rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
            assertEquals(sha256(target), digest)
        }

    /**
     * The real fat jar: ~44,000 blocks and 35 zero-length blocks at once.
     *
     * Planned against itself, so this proves what only real scale can: that 32k central-directory
     * records parse, that the block lengths tile the body with no gap the coverage check would
     * reject, that zero-length blocks do not break the copy loop, and that the digest of a full
     * copy is right. It proves nothing at all about the fetch path — the fetch list is empty — nor
     * about ranges interleaving with reused blocks, and it *cannot* catch a mis-paired block:
     * every block is paired with itself, and identity-equal blocks hold equal bytes anyway. Those
     * are what `rebuilds a jar whose entries both moved and changed` and
     * `reads a reused block from its local offset` cover, on fixtures that need no 119 MB
     * artifact. Digests are compared rather than `readBytes()`, which on a 119 MB file would hold
     * both copies in the test heap.
     */
    @Tag("fatjar")
    @Test
    fun `rebuilds the real fat jar byte for byte`() =
        runTest {
            val real = JarFixtures.requireShadowJar()
            val out = File(dir, "rebuilt-all.jar")

            val digest = rebuild(real, real, out)()

            assertEquals(real.length(), out.length())
            assertEquals(sha256(real), digest)
            assertEquals(sha256(real), sha256(out))
        }

    /**
     * Every reused block sits at a different offset locally than in the target.
     *
     * A padding entry the target does not have shifts the whole body, so `BlockCopy.localOffset`
     * and `BlockCopy.block.offset` disagree on every reused block. Without this the suite is blind
     * to the pairing: in every other fixture the changed entry is appended last, so the two offsets
     * happen to coincide and reading from the target offset rebuilds the jar just as well.
     */
    @Test fun `reads a reused block from its local offset, not its target offset`() =
        runTest {
            val local =
                JarFixtures.writeJar(
                    File(dir, "local.jar"),
                    listOf("aaa/pad.txt" to "x".repeat(5000)) + JarFixtures.THREE_ENTRIES,
                )
            val target = JarFixtures.writeJar(File(dir, "target.jar"), JarFixtures.THREE_ENTRIES)
            val out = File(dir, "rebuilt.jar")
            val plan = DeltaPlanner().plan(JarStructure.of(local), JarStructure.of(target))
            assertTrue(
                plan.reuse.any { it.localOffset != it.block.offset },
                "the fixture must place reused blocks at different offsets, or it tests nothing",
            )

            rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
        }

    /**
     * The shape of a real upgrade, at fixture size: entries both move and change.
     *
     * A padding entry only the target has shifts everything, and two entries in the middle change,
     * so reused blocks and fetched ranges interleave in target order and the coalesced range spans
     * several blocks — overwriting reused blocks that the gap tolerance swallowed. That is the
     * whole delta path, and the real-jar test covers none of it: planned against itself, it fetches
     * nothing.
     */
    @Test fun `rebuilds a jar whose entries both moved and changed`() =
        runTest {
            val body = listOf("a/A" to "body a", "b/B" to "body b", "c/C" to "body c", "d/D" to "body d")
            val local = JarFixtures.writeJar(File(dir, "local.jar"), body)
            val changed =
                body.map { (name, text) ->
                    if (name == "b/B" || name == "d/D") name to "$text CHANGED" else name to text
                }
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    listOf("aaa/pad.txt" to "x".repeat(3000)) + changed,
                )
            val out = File(dir, "rebuilt.jar")
            val targetStructure = JarStructure.of(target)
            val plan = DeltaPlanner().plan(JarStructure.of(local), targetStructure)
            assertTrue(plan.reuse.isNotEmpty() && plan.fetch.isNotEmpty(), "both paths must run: $plan")
            assertTrue(
                plan.reuse.any { it.localOffset != it.block.offset },
                "the fixture must move the reused blocks",
            )
            assertTrue(
                plan.fetch.any { range ->
                    targetStructure.blocks.count { it.offset >= range.start && it.offset < range.endExclusive } > 1
                },
                "a coalesced range must span more than one block: ${plan.fetch}",
            )

            val digest = rebuild(local, target, out)()

            assertTrue(target.readBytes().contentEquals(out.readBytes()))
            assertEquals(sha256(target), digest)
        }

    /**
     * A plan that does not cover the whole body is refused before anything is written.
     *
     * `RandomAccessFile.setLength` pre-extends with zeroes, so a dropped block — or a plan built
     * against a different target — otherwise produces a jar with a silent hole, caught only much
     * later as a digest mismatch that can say nothing about which byte was missed.
     */
    @Test fun `a plan that leaves a hole in the body is refused`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    JarFixtures.THREE_ENTRIES + ("org/example/C.class" to "new class"),
                )
            val out = File(dir, "rebuilt.jar")
            val structure = JarStructure.of(target)
            val full = DeltaPlanner().plan(JarStructure.of(local), structure)
            val dropped = full.reuse.last { it.block.length > 0 }
            val holed = full.copy(reuse = full.reuse - dropped)

            val failure =
                assertFailsWith<IllegalStateException> {
                    DeltaReconstructor(serve(target)).reconstruct(local, structure, holed, out)
                }

            assertTrue(
                failure.message!!.contains("${dropped.block.offset}"),
                "the refusal must name the first byte left unwritten: ${failure.message}",
            )
            assertFalse(out.exists(), "nothing may be written from a plan that cannot be completed")
            Unit
        }

    @Test fun `a failed fetch leaves no file behind`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val changed =
                JarFixtures.THREE_ENTRIES.map {
                    if (it.first == "org/example/B.class") it.first to "class B CHANGED" else it
                }
            val target = JarFixtures.writeJar(File(dir, "target.jar"), changed)
            val out = File(dir, "rebuilt.jar")

            assertFailsWith<IllegalStateException> {
                rebuild(local, target, out) { error("the connection dropped") }()
            }

            assertFalse(out.exists(), "a failed reconstruction must not leave a jar a later step could stage")
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("rebuilt.jar") }, "no partial file may survive")
            Unit
        }

    @Test fun `an existing output is not left half-overwritten by a failed fetch`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    JarFixtures.THREE_ENTRIES + ("org/example/C.class" to "new class"),
                )
            val out = File(dir, "rebuilt.jar")
            out.writeText("an earlier attempt")

            assertFailsWith<IllegalStateException> {
                rebuild(local, target, out) { error("the connection dropped") }()
            }

            assertEquals("an earlier attempt", out.readText())
        }

    @Test fun `a short fetch answer is refused rather than written`() =
        runTest {
            val local = JarFixtures.writeJar(File(dir, "local.jar"), JarFixtures.THREE_ENTRIES)
            val target =
                JarFixtures.writeJar(
                    File(dir, "target.jar"),
                    JarFixtures.THREE_ENTRIES + ("org/example/C.class" to "new class"),
                )
            val out = File(dir, "rebuilt.jar")
            val full = serve(target)

            val failure =
                assertFailsWith<IllegalStateException> {
                    rebuild(local, target, out) { range -> full(range).dropLast(1).toByteArray() }()
                }

            assertTrue(failure.message!!.contains("bytes"), "the refusal must say what was short: ${failure.message}")
            assertFalse(out.exists())
            Unit
        }
}
