package org.example.project.features.update.domain.models

import org.example.project.core.utils.SemanticVersion

/**
 * A release on GitHub that is strictly newer than the running build.
 *
 * Only asset *ids* are kept, never `browser_download_url`: that URL takes no credential on a
 * private repository, so the asset API (`/releases/assets/{id}`) is the only path that works for
 * both channels.
 */
data class AvailableUpdate(
    val version: SemanticVersion,
    val releaseNotes: String,
    /** Asset id of the server zip, for the asset-API download path. */
    val serverAssetId: Long,
    /** Asset id of SHA256SUMS. Null when the release predates checksum publication. */
    val checksumAssetId: Long?,
    val sizeBytes: Long,
    /**
     * Asset id of the bare `pos-server.jar`. Null on a release that predates it.
     *
     * The update replaces only this file, so it is both the delta target and the cheaper fallback
     * when a delta cannot be built.
     */
    val jarAssetId: Long? = null,
    /** Asset id of `pos-server-<version>.jar.cdir.gz`. Null on a release that predates it. */
    val structureAssetId: Long? = null,
    /**
     * Asset id of the Android APK. Null when the release publishes none, or publishes several --
     * guessing which is ours is how the wrong build reaches a till.
     */
    val apkAssetId: Long? = null,
    /** Asset id of `android-client.json`. Null on a release that predates it; the APK is then not offered. */
    val clientInfoAssetId: Long? = null,
)
