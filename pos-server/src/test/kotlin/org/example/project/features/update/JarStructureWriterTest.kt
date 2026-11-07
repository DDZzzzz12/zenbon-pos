package org.example.project.features.update

import org.example.project.features.update.data.service.JarStructure
import org.example.project.features.update.data.service.JarStructureWriter
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The build's half of the delta-update round trip.
 *
 * Its own file rather than more cases in [JarStructureTest]: that file is the parser's suite and
 * already runs long, and these tests are about the *asset* — what the build publishes and what an
 * unattended updater then has to parse.
 */
class JarStructureWriterTest {
    private val dir: File = Files.createTempDirectory("jarstructurewriter").toFile()

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun `the asset gunzips to a directory the client's parser accepts`() {
        val jar = JarFixtures.writeJar(File(dir, "small.jar"), JarFixtures.THREE_ENTRIES)

        assertRoundTrips(jar, File(dir, "small.jar.cdir.gz"))
    }

    @Test fun `a zip64 jar round-trips too`() {
        val jar = JarFixtures.writeZip64Jar(File(dir, "zip64.jar"), JarFixtures.THREE_ENTRIES)

        assertRoundTrips(jar, File(dir, "zip64.jar.cdir.gz"))
    }

    @Tag("fatjar")
    @Test
    fun `the real fat jar's asset agrees with the jar it was written from`() {
        val real = JarFixtures.requireShadowJar()

        val structure = assertRoundTrips(real, File(dir, "pos-server.jar.cdir.gz"))

        assertTrue(structure.blocks.size > 10_000, "expected a fat jar, got ${structure.blocks.size} blocks")
    }

    @Test fun `a jar whose directory is truncated fails the build instead of shipping`() {
        val jar = JarFixtures.writeJar(File(dir, "broken.jar"), JarFixtures.THREE_ENTRIES)
        val bytes = jar.readBytes()
        val directoryOffset = JarStructure.of(jar).centralDirectoryOffset.toInt()
        // Drop one record's worth of bytes out of the middle of the directory: the tail scan still
        // finds the EOCD and slices happily, so only the consumer's parser catches it.
        jar.writeBytes(bytes.copyOfRange(0, directoryOffset) + bytes.copyOfRange(directoryOffset + 60, bytes.size))
        val target = File(dir, "broken.jar.cdir.gz")

        val failure = assertFailsWith<IllegalArgumentException> { JarStructureWriter.write(jar, target) }

        assertContains(failure.message!!, "central directory declares 3 entries")
        assertTrue(!target.exists(), "a rejected jar must leave no asset behind")
    }

    /** Writes the asset, reads it back the way the client does, and compares against the jar. */
    private fun assertRoundTrips(
        jar: File,
        target: File,
    ): JarStructure {
        val expected = JarStructure.of(jar)

        JarStructureWriter.write(jar, target)

        val blob = GZIPInputStream(target.inputStream().buffered()).use { it.readBytes() }
        val parsed = JarStructure.parse(blob)
        assertEquals(expected.blocks, parsed.blocks)
        assertEquals(expected.centralDirectoryOffset, parsed.centralDirectoryOffset)
        assertEquals(expected.totalSize, parsed.totalSize)
        assertEquals(jar.length(), parsed.totalSize)
        return parsed
    }
}
