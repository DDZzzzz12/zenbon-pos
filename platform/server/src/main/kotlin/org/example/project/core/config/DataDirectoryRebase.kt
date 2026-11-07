package org.example.project.core.config

import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Replaces the old data-directory prefix of a stored absolute path after `--migrate-data-dir`.
 *
 * Only a path equal to or beneath [oldBase] moves; a path the operator pointed elsewhere (a USB
 * stick), a sibling that merely shares the prefix, and blank or relative paths are returned as is.
 * Because an already-rebased path no longer lies inside the old base, running it twice is a no-op.
 */
object DataDirectoryRebase {
    /** Marker the migration writes under the new base; holds the old base path. */
    const val MARKER = ".data/previous-data-dir"

    fun rebase(
        path: String,
        oldBase: String,
        newBase: String,
    ): String {
        if (path.isBlank() || oldBase.isBlank() || newBase.isBlank()) return path
        val parsed = parse(path)?.takeIf { it.isAbsolute } ?: return path
        val old = parse(oldBase)?.takeIf { it.isAbsolute }?.normalize() ?: return path
        val normalized = parsed.normalize()
        if (!normalized.startsWith(old)) return path
        val relative = old.relativize(normalized).toString()
        return if (relative.isEmpty()) newBase.trimEnd('/', '\\') else File(newBase, relative).path
    }

    private fun parse(value: String): Path? =
        try {
            Paths.get(value)
        } catch (_: InvalidPathException) {
            null
        }
}
