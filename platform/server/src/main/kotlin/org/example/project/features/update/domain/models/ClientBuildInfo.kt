package org.example.project.features.update.domain.models

import kotlinx.serialization.Serializable

/**
 * The two numbers a release publishes about its Android build, in `android-client.json`.
 *
 * `versionCode` is what a till compares; reading it from a published file rather than out of the
 * binary `AndroidManifest.xml` inside the APK keeps the server free of an APK parser. The release
 * workflow checks the file against the real APK before publishing.
 */
@Serializable
data class ClientBuildInfo(
    val versionCode: Int,
    val versionName: String,
)
