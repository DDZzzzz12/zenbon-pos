package org.example.project.features.appupdate.domain

/**
 * Whether the server's Android build is worth installing. `versionCode` only -- never the version
 * name: a server-only release changes the name but not the code, and must not prompt a till.
 */
object AppUpdateDecision {
    fun isNewer(
        installed: Long,
        offered: Int,
    ): Boolean = offered > installed
}
