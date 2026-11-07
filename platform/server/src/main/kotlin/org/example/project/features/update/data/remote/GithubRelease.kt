package org.example.project.features.update.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of GitHub's release payload the update check reads. Unknown fields are ignored, so
 * GitHub may keep adding them.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)
