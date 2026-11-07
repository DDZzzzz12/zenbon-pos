package org.example.project.core.utils

/**
 * A release version, used to decide whether a GitHub tag is newer than the running build.
 *
 * String comparison is wrong here -- "1.0.9" sorts above "1.0.11" -- so the segments are
 * compared numerically. A prerelease suffix is recorded but does not participate in ordering;
 * the update check filters prereleases out before it ever compares them.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch" + (prerelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-(.+))?$""")

        /** Returns null for anything that is not a version, rather than throwing. */
        fun parse(raw: String): SemanticVersion? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemanticVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].ifEmpty { "0" }.toInt(),
                prerelease = m.groupValues[4].ifEmpty { null },
            )
        }
    }
}
