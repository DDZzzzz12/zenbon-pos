package org.example.project.infrastructure.plugins

import io.ktor.http.content.EntityTagVersion
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Content-derived `ETag` for the classpath resources behind the browser apps (`/admin`, `/pos`).
 *
 * The tag is a SHA-256 of the bytes, not a timestamp or the file length: a shadow JAR built with
 * reproducible timestamps gives every entry the same modification time, so a validator based on
 * time and size would answer 304 for a changed file of equal size and pin a browser to a stale
 * bundle. Hashing costs one read per file; the result is kept for as long as the file's
 * length and modification time are unchanged (so a dev run from a directory still picks up a rebuild).
 */
internal object StaticResourceValidators {
    private data class Fingerprint(
        val length: Long,
        val modifiedAt: Long,
    )

    private data class Entry(
        val fingerprint: Fingerprint,
        val tag: EntityTagVersion,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun etagOf(resource: URL): EntityTagVersion {
        val connection = resource.openConnection()
        val fingerprint = Fingerprint(connection.contentLengthLong, connection.lastModified)
        val key = resource.toExternalForm()
        cache[key]?.takeIf { it.fingerprint == fingerprint }?.let { return it.tag }
        val tag = EntityTagVersion(sha256Hex(connection))
        cache[key] = Entry(fingerprint, tag)
        return tag
    }

    private fun sha256Hex(connection: java.net.URLConnection): String {
        val digest = MessageDigest.getInstance("SHA-256")
        connection.getInputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
