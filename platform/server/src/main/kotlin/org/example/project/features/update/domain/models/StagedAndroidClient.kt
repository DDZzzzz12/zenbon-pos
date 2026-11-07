package org.example.project.features.update.domain.models

import kotlinx.serialization.Serializable

/** What the client store records about the APK it holds. Also its on-disk metadata file. */
@Serializable
data class StagedAndroidClient(
    /** The release it came from, e.g. `1.2.0`; the store's idempotency key. */
    val releaseVersion: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)
