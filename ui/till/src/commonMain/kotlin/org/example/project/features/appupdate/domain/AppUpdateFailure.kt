package org.example.project.features.appupdate.domain

/** Why a download or install failed on the device. Server errors are `UiError`, not these. */
enum class AppUpdateFailure {
    /** Transport failure or an unexpected answer while downloading. */
    DOWNLOAD_FAILED,

    /** The bytes did not match the published SHA-256; the file was deleted. */
    HASH_MISMATCH,

    /** "Install unknown apps" is not allowed for this app. */
    PERMISSION_MISSING,

    /** The user dismissed Android's install confirmation. */
    INSTALL_CANCELLED,

    /** Android refused the install (for example a different signing key). */
    INSTALL_FAILED,
}
