package org.example.project.features.update

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds jars shaped like the real shadowJar output: entries written through a stream (so each
 * carries a trailing data descriptor) and duplicate names allowed.
 */
object JarFixtures {
    /**
     * The locally built fat jar, or a SKIPPED test.
     *
     * `assumeTrue` rather than an early `return`: a returning test reports as PASSED, so a missing
     * artifact would look like green coverage of the real jar instead of no coverage at all.
     */
    fun requireShadowJar(): File {
        val real = realShadowJar()
        assumeTrue(real != null, "no shadowJar in build/libs — run :pos-server:shadowJar")
        return real!!
    }

    private fun realShadowJar(): File? = File("build/libs").listFiles()?.firstOrNull { it.name.endsWith("-all.jar") }

    fun writeJar(
        target: File,
        entries: List<Pair<String, String>>,
    ): File {
        val occurrences = mutableMapOf<String, Int>()
        val aliases = mutableListOf<Pair<String, String>>()
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            entries.forEach { (name, body) ->
                val occurrence = occurrences.merge(name, 1, Int::plus)!!
                val written =
                    if (occurrence == 1) {
                        name
                    } else {
                        aliasFor(name, occurrence).also { aliases += it to name }
                    }
                out.putNextEntry(ZipEntry(written).also { it.time = timeOf(occurrence) })
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        if (aliases.isNotEmpty()) restoreDuplicateNames(target, aliases)
        return target
    }

    /**
     * A fixed last-modified time per occurrence of a name, so bytes are reproducible and two
     * same-named entries still differ.
     *
     * Fixed, because the default is "now": two fixtures written a second apart could otherwise land
     * on either side of the 2-second DOS-time granularity and stop being byte-comparable. Derived
     * from the occurrence rather than the position, so an entry keeps its bytes when another entry
     * is inserted before it — which is the whole point of the fixtures where blocks move. Differing
     * per occurrence is what gives two same-name, same-content entries the same
     * [org.example.project.features.update.domain.models.JarBlock.identity] but different bytes, so
     * that pairing them by identity instead of by position is observable.
     */
    private fun timeOf(occurrence: Int): Long = BASE_TIME + occurrence * 2_000L

    /** 2021-01-01T00:00:00Z, well inside the DOS time range. */
    private const val BASE_TIME = 1_609_459_200_000L

    /**
     * A stand-in of exactly the same length as [name].
     *
     * `ZipOutputStream` refuses to write one name twice, so a repeated entry goes in under an
     * alias that [restoreDuplicateNames] swaps back afterwards. Equal length is what makes the
     * swap safe: no name-length field, size or offset in the archive changes.
     */
    private fun aliasFor(
        name: String,
        occurrence: Int,
    ): String {
        val marker = "~$occurrence"
        require(name.length > marker.length) { "name '$name' is too short to alias" }
        return name.dropLast(marker.length) + marker
    }

    /** Rewrites each alias back to its real name, in both the local header and the directory. */
    private fun restoreDuplicateNames(
        target: File,
        aliases: List<Pair<String, String>>,
    ) {
        // ISO-8859-1 maps every byte to exactly one char and back, so this is a byte-level edit.
        var bytes = String(target.readBytes(), Charsets.ISO_8859_1)
        aliases.forEach { (alias, name) -> bytes = bytes.replace(alias, name) }
        target.writeBytes(bytes.toByteArray(Charsets.ISO_8859_1))
    }

    val THREE_ENTRIES =
        listOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
            "org/example/A.class" to "class A body",
            "org/example/B.class" to "class B body",
        )

    /**
     * Writes a genuinely zip64 archive: a zip64 end-of-central-directory record, its locator and a
     * zip64 extra field on every entry.
     *
     * `java.util.zip` only emits those once an archive actually exceeds a 32-bit limit, which no
     * fixture can afford. `Zip64Mode.ALWAYS` forces the records and the per-entry extra fields onto
     * a tiny archive — but not the *last* zip64 difference: it leaves the true 32-bit offset in the
     * classic EOCD, so a reader never consults the zip64 record. [blankClassicDirectoryOffset]
     * supplies that final step, and both are needed for the parser's zip64 paths to run.
     */
    fun writeZip64Jar(
        target: File,
        entries: List<Pair<String, String>>,
    ): File {
        ZipArchiveOutputStream(target).use { out ->
            out.setUseZip64(Zip64Mode.Always)
            entries.forEach { (name, body) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(body.toByteArray())
                out.closeArchiveEntry()
            }
        }
        blankClassicDirectoryOffset(target)
        return target
    }

    /**
     * Writes 0xFFFFFFFF over the classic EOCD's central-directory offset, the last thing a real
     * zip64 archive differs by.
     *
     * `Zip64Mode.ALWAYS` emits the zip64 record, its locator and the per-entry extra fields, but
     * still leaves the true 32-bit offset in the classic EOCD because the archive is small — so a
     * reader never consults the zip64 record, and the fixture would silently prove nothing. The
     * zip64 record already carries the same offset, so blanking the field is exactly the shape a
     * >4 GiB archive has, and every zip reader (including `java.util.zip`) follows it.
     */
    private fun blankClassicDirectoryOffset(target: File) {
        val bytes = target.readBytes()
        val eocd =
            (bytes.size - 4 downTo 0).firstOrNull { at ->
                bytes.copyOfRange(at, at + 4).contentEquals(EOCD_SIGNATURE)
            } ?: error("no end-of-central-directory record in ${target.name}")
        repeat(4) { i -> bytes[eocd + 16 + i] = 0xFF.toByte() }
        target.writeBytes(bytes)
    }

    /** `PK\x05\x06` — the classic end-of-central-directory record signature. */
    private val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    /**
     * Two same-name entries with the *same* body, so their blocks share an identity.
     *
     * [WITH_DUPLICATES] gives its two `META-INF/LICENSE` entries different bodies, which makes
     * their `JarBlock.identity` differ too — so a lookup keyed by identity pairs them correctly by
     * accident and the fixture proves only that a *name*-keyed lookup is wrong. Here name, CRC and
     * both sizes agree and only the last-modified stamp differs (see [timeOf]), exactly as two
     * `META-INF/LICENSE` files shaded out of two dependencies do: identity is equal, bytes are not,
     * and only the planner's positional pairing rebuilds them.
     */
    val WITH_SAME_CONTENT_DUPLICATES =
        listOf(
            "META-INF/LICENSE" to "the same license text",
            "org/example/A.class" to "class A body",
            "META-INF/LICENSE" to "the same license text",
            "org/example/B.class" to "class B body",
        )

    /** Two entries share a name, exactly as shadowJar's DuplicatesStrategy.INCLUDE produces. */
    val WITH_DUPLICATES =
        listOf(
            "META-INF/LICENSE" to "license one",
            "org/example/A.class" to "class A body",
            "META-INF/LICENSE" to "license two, longer",
        )
}
