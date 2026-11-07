package org.example.project.core.domain.fileBrowser

import org.example.project.core.domain.fileBrowser.models.FileEntry

/**
 * There is no filesystem to walk from a browser page.
 *
 * Listings are empty and the home directory is the empty string — honest answers for a host with no
 * such concept. Inventing plausible paths would let the file-browser UI offer navigation that can
 * never succeed. [readFileBytes] throws, because no caller can do anything useful with a fabricated
 * empty file.
 */
actual object FileSystemBrowser {
    actual suspend fun listDirectory(path: String): List<FileEntry> = emptyList()

    actual suspend fun getThumbnail(
        path: String,
        size: Int,
    ): ByteArray? = null

    actual suspend fun readFileBytes(path: String): ByteArray =
        throw UnsupportedOperationException(
            "The browser preview has no filesystem access — file reads are unavailable here.",
        )

    actual fun getHomeDirectory(): String = ""

    actual fun getPictureDirectories(): List<String> = emptyList()
}
