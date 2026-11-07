package org.example.project.features.update.domain.models

import java.io.File

/** The staged APK and the digest it was verified against, for the download route's `X-SHA256` header. */
data class AndroidClientDownload(
    val file: File,
    val sha256: String,
)
