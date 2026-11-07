package org.example.project.features.update.data.service

import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Writes a jar's central directory to a gzipped `.cdir.gz` blob — the structure asset a delta
 * client fetches instead of the whole jar.
 *
 * Gzip rather than a stronger codec on purpose: it costs no dependency on either side, and the
 * directory compresses well enough that a stronger codec would save a fraction of an already small
 * asset. (Measured 2026-09-08 on the 119 MB fat jar: a 4,642,637-byte directory gzips to
 * 925,604 bytes.)
 *
 * Invoked by Gradle (`writeJarStructure`) rather than living in the build script, so the build and
 * the client share one definition of what the directory is.
 */
object JarStructureWriter {
    fun write(
        jar: File,
        target: File,
    ): File {
        val directory = JarStructure.readCentralDirectory(jar)
        // Deliberately runs the CONSUMER's parser, not just the tail scan that produced these
        // bytes: an unattended updater is the only other thing that ever parses them, so a
        // mis-sliced directory must fail the build here rather than a customer's update later.
        JarStructure.parse(directory)
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).use { it.write(directory) }
        return target
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: JarStructureWriter <jar> <target.cdir.gz>" }
        val written = write(File(args[0]), File(args[1]))
        println("wrote ${written.absolutePath} (${written.length()} bytes)")
    }
}
