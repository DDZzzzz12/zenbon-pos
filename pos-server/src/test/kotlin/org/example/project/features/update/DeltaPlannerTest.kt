package org.example.project.features.update

import org.example.project.features.update.data.service.DeltaPlanner
import org.example.project.features.update.data.service.JarStructure
import org.example.project.features.update.domain.models.ByteRange
import org.example.project.features.update.domain.models.JarBlock
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaPlannerTest {
    private val dir: File = Files.createTempDirectory("deltaplanner").toFile()

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private fun structure(
        name: String,
        entries: List<Pair<String, String>>,
    ) = JarStructure.of(JarFixtures.writeJar(File(dir, name), entries))

    /**
     * A structure with hand-placed blocks, so a test can state offsets and lengths exactly.
     *
     * The zero-length blocks shadowJar produces exist only in the 120 MB fat jar, where offsets
     * are whatever the build happened to emit; the gap tolerance is what is under test here, and
     * it needs the gaps to be chosen rather than observed. Only [JarStructure.blocks] is read by
     * the planner, so the directory blob is empty.
     */
    private fun structureOf(vararg blocks: JarBlock) = JarStructure(blocks.toList(), ByteArray(0), 0)

    private fun block(
        name: String,
        offset: Long,
        length: Long,
    ) = JarBlock(name = name, crc = 1L, compressedSize = length, uncompressedSize = length, offset = offset, length = length)

    @Test fun `an identical jar needs nothing fetched`() {
        val local = structure("a.jar", JarFixtures.THREE_ENTRIES)
        val target = structure("b.jar", JarFixtures.THREE_ENTRIES)

        val plan = DeltaPlanner().plan(local, target)

        assertEquals(target.blocks.size, plan.reuse.size)
        assertTrue(plan.fetch.isEmpty())
        assertEquals(0L, plan.fetchBytes)
    }

    @Test fun `only the changed entry is fetched`() {
        val local = structure("a.jar", JarFixtures.THREE_ENTRIES)
        val changed =
            JarFixtures.THREE_ENTRIES.map {
                if (it.first == "org/example/B.class") it.first to "class B CHANGED" else it
            }
        val target = structure("b.jar", changed)

        val plan = DeltaPlanner().plan(local, target)

        assertEquals(2, plan.reuse.size)
        assertEquals(1, plan.fetch.size)
        assertTrue(plan.fetchBytes > 0)
    }

    /**
     * A zero-length missing block must produce no range at all.
     *
     * Fetching it would gain nothing — it contributes no bytes to the reconstruction either way —
     * and `ByteRange(5000, 5000).toHeaderValue()` is `bytes=5000-4999`, a malformed `Range` most
     * CDNs answer with 400 for the whole request. One such block would fail the entire update.
     */
    @Test fun `a zero-length missing block is not fetched`() {
        val plan = DeltaPlanner().plan(local = structureOf(), target = structureOf(block("shared", offset = 5000, length = 0)))

        assertTrue(plan.reuse.isEmpty())
        assertTrue(plan.fetch.isEmpty(), "an empty range would render as bytes=5000-4999")
        assertEquals(0L, plan.fetchBytes)
    }

    /**
     * A zero-length block must not act as a stepping stone across a gap wider than the tolerance.
     *
     * Coalescing merges neighbours up to [DeltaPlanner.DEFAULT_GAP_TOLERANCE] apart. An empty
     * range sitting in a wider gap is within tolerance of both sides, so it chains them and the
     * effective tolerance doubles — here 8,000 unwanted bytes under a 4,096-byte promise, and more
     * with each further empty block.
     */
    @Test fun `a zero-length block does not bridge a gap wider than the tolerance`() {
        val target =
            structureOf(
                block("a", offset = 0, length = 1000),
                block("shared", offset = 5000, length = 0),
                block("b", offset = 9000, length = 1000),
            )

        val plan = DeltaPlanner(gapToleranceBytes = 4096).plan(local = structureOf(), target = target)

        assertEquals(2, plan.fetch.size, "the 8,000-byte gap is twice the tolerance")
        assertEquals(listOf(ByteRange(0, 1000), ByteRange(9000, 10000)), plan.fetch)
        assertEquals(2000L, plan.fetchBytes)
    }

    /** `Range` is inclusive on both ends; [ByteRange] is half-open. */
    @Test fun `a byte range renders an inclusive Range header`() {
        assertEquals("bytes=0-9", ByteRange(0, 10).toHeaderValue())
    }

    /**
     * The reused block carries the offset it is read from, not the one it is written to.
     *
     * The two jars deliberately place `org/example/A.class` at different offsets — the local one
     * behind a leading entry the target does not have — so a plan that reported the target offset
     * for both would be visibly wrong here. The reassembler seeks [localOffset] in the installed
     * jar, and re-deriving that pairing later by name or identity would collapse duplicates.
     */
    @Test fun `a reused block carries the local offset it is read from`() {
        val reused = "org/example/A.class" to "class A body"
        val local = structure("a.jar", listOf("org/example/Z.class" to "a leading entry the target lacks", reused))
        val target = structure("b.jar", listOf(reused))

        val plan = DeltaPlanner().plan(local, target)

        val copy = plan.reuse.single { it.block.name == "org/example/A.class" }
        val localBlock = local.blocks.single { it.name == "org/example/A.class" }
        val targetBlock = target.blocks.single { it.name == "org/example/A.class" }
        assertTrue(localBlock.offset != targetBlock.offset, "the fixture must place the block differently in each jar")
        assertEquals(localBlock.offset, copy.localOffset)
        assertEquals(targetBlock.offset, copy.block.offset)
    }

    @Test fun `duplicate names are matched one for one, not collapsed`() {
        val local = structure("a.jar", JarFixtures.WITH_DUPLICATES)
        val target = structure("b.jar", JarFixtures.WITH_DUPLICATES)

        val plan = DeltaPlanner().plan(local, target)

        assertEquals(3, plan.reuse.size)
        assertEquals(2, plan.reuse.count { it.block.name == "META-INF/LICENSE" })
    }

    /**
     * One local block must not satisfy two target blocks: the pool is consumed, not looked up.
     *
     * The local jar holds one `META-INF/LICENSE`, the target two identical ones. A name- or
     * identity-keyed lookup reuses the single local block twice and fetches nothing; consuming it
     * leaves the second unmatched, which is the only answer a client can actually reconstruct.
     */
    @Test fun `a second copy of a duplicated name is fetched, not reused twice`() {
        val local = structure("a.jar", listOf("META-INF/LICENSE" to "license one"))
        val target =
            structure(
                "b.jar",
                listOf("META-INF/LICENSE" to "license one", "META-INF/LICENSE" to "license one"),
            )

        val plan = DeltaPlanner().plan(local, target)

        assertEquals(1, plan.reuse.size)
        assertEquals(1, plan.fetch.size)
    }

    @Test fun `ranges separated by an unchanged entry are coalesced within the tolerance`() {
        // A and C change, B does not: the two fetches are separated by B's block, so a zero
        // tolerance keeps them apart while a generous one swallows B to save a round trip.
        val entries =
            listOf(
                "org/example/A.class" to "class A body",
                "org/example/B.class" to "class B body",
                "org/example/C.class" to "class C body",
            )
        val local = structure("a.jar", entries)
        val target =
            structure(
                "b.jar",
                listOf(
                    "org/example/A.class" to "class A CHANGED",
                    "org/example/B.class" to "class B body",
                    "org/example/C.class" to "class C CHANGED",
                ),
            )

        val coalesced = DeltaPlanner(gapToleranceBytes = 4096).plan(local, target)
        val exact = DeltaPlanner(gapToleranceBytes = 0).plan(local, target)

        assertEquals(1, coalesced.fetch.size)
        assertEquals(2, exact.fetch.size)
        assertTrue(coalesced.fetchBytes > exact.fetchBytes, "coalescing trades bytes for round trips")
    }

    @Test fun `a jar with nothing in common reports the whole file as one range`() {
        val local = structure("a.jar", JarFixtures.THREE_ENTRIES)
        val target = structure("b.jar", listOf("totally/Other.class" to "unrelated"))

        val plan = DeltaPlanner().plan(local, target)

        assertTrue(plan.reuse.isEmpty())
        assertEquals(1, plan.fetch.size)
        assertEquals(target.centralDirectoryOffset, plan.fetchBytes)
    }

    /**
     * The real 44,000-entry fat jar planned against itself must need nothing.
     *
     * The fixtures hold three entries; this exercises duplicate names, the 35 zero-length blocks
     * shadowJar's shared local blocks produce, and every identity at once. It is exact by
     * construction: an upgrade that reuses fewer blocks than a jar shares with itself is a bug in
     * the identity key, not a tuning question.
     */
    @Tag("fatjar")
    @Test
    fun `the real fat jar planned against itself reuses everything`() {
        val real = JarStructure.of(JarFixtures.requireShadowJar())

        val plan = DeltaPlanner().plan(real, real)

        assertEquals(real.blocks.size, plan.reuse.size)
        assertTrue(plan.fetch.isEmpty(), "planning a jar against itself must fetch nothing")
        assertEquals(0L, plan.fetchBytes)
        assertTrue(real.blocks.any { it.length == 0L }, "the fixture-free case must include shared local blocks")
    }

    /**
     * A coalesced range is cut into contiguous pieces once it passes the cap.
     *
     * Merging is unbounded: with the bail-out fraction at 0.5 one range may legitimately span half
     * the jar, and it is materialised twice — the ktor channel's buffer and the `ByteArray` the
     * reconstructor writes. `OutOfMemoryError` is an `Error`, so `stageByDelta`'s `catch (Exception)`
     * would NOT fall back to the full download; it would take the process down. The cap is what
     * keeps the allocation bounded.
     *
     * The pieces must tile the original span exactly — the reconstructor requires the plan to cover
     * the body with no holes, and each piece is written at its own absolute offset.
     */
    @Test fun `a coalesced range larger than the cap is split into contiguous pieces`() {
        val planner = DeltaPlanner(gapToleranceBytes = 4096, maxRangeBytes = 1000)
        val target =
            structureOf(
                block("a", offset = 0, length = 900),
                block("b", offset = 900, length = 2600),
            )

        val plan = planner.plan(local = structureOf(), target = target)

        assertTrue(plan.fetch.all { it.length <= 1000 }, "a range above the cap must be split: ${plan.fetch}")
        assertEquals(4, plan.fetch.size)
        assertEquals(0L, plan.fetch.first().start)
        assertEquals(3500L, plan.fetch.last().endExclusive)
        plan.fetch.zipWithNext { left, right ->
            assertEquals(left.endExclusive, right.start, "the pieces must be contiguous: ${plan.fetch}")
        }
        assertEquals(3500L, plan.fetchBytes)
    }

    /** The cap must not touch a plan that stays under it — the real upgrade fetches ~1.47 MB. */
    @Test fun `a range under the cap is left whole`() {
        val planner = DeltaPlanner(gapToleranceBytes = 4096, maxRangeBytes = 1000)

        val plan = planner.plan(local = structureOf(), target = structureOf(block("a", offset = 0, length = 1000)))

        assertEquals(1, plan.fetch.size)
        assertEquals(ByteRange(0, 1000), plan.fetch.single())
    }

    /** 16 MiB: two copies of it are unremarkable, and it is far above any real plan's ranges. */
    @Test fun `the default range cap is sixteen mebibytes`() {
        assertEquals(16L * 1024 * 1024, DeltaPlanner.DEFAULT_MAX_RANGE)
    }
}
