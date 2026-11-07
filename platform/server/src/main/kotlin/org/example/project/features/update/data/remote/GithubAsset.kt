package org.example.project.features.update.data.remote

import kotlinx.serialization.Serializable

/**
 * One file attached to a release. Only the [id] is usable on a private repository -- the asset API
 * takes a bearer token, `browser_download_url` does not.
 */
@Serializable
data class GithubAsset(
    val id: Long,
    val name: String,
    val size: Long = 0,
)
