package org.example.project.core.utils

/**
 * Pure path rules for the backup "copy to" target, shared by the server guard and the back office
 * form so both refuse the same input. Strings only — no file system access.
 */
object BackupCopyPathRules {
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

    fun isAbsolute(path: String): Boolean = path.startsWith("/") || path.startsWith("\\") || WINDOWS_ABSOLUTE.matches(path)

    fun hasParentSegment(path: String): Boolean = path.replace('\\', '/').split('/').any { it == ".." }

    /** True when [path] equals [folder] or lies beneath it (separator-aware, trailing slashes ignored). */
    fun isSameOrInside(
        path: String,
        folder: String,
    ): Boolean {
        val p = normalize(path)
        val f = normalize(folder)
        if (f.isEmpty()) return false
        return p == f || p.startsWith("$f/")
    }

    private fun normalize(value: String): String =
        value
            .trim()
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
            .trimEnd('/')
}
