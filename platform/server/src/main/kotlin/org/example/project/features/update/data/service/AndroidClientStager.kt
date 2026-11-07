package org.example.project.features.update.data.service

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.example.project.features.update.domain.models.AvailableUpdate
import org.example.project.features.update.domain.models.ClientBuildInfo
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Fetches a release's Android build into a [ClientArtifactStore].
 *
 * **Best effort, by design.** A till update is an addition to a server update, never a condition
 * of it: if the APK is missing, will not verify or cannot be downloaded, the server update must
 * still stage and install exactly as it did before this class existed. So [stage] logs and returns
 * for every expected failure and only rethrows cancellation.
 *
 * Idempotent per release: if the store already holds this release's APK nothing is requested --
 * the nightly job and a manual stage can both call it freely.
 */
class AndroidClientStager(
    private val releases: GithubReleaseClient,
    private val store: ClientArtifactStore,
) {
    private val log = LoggerFactory.getLogger(AndroidClientStager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun stage(
        repo: GithubReleaseClient.Repo,
        token: String?,
        update: AvailableUpdate,
        progress: UpdateDownloadTracker? = null,
    ) {
        val release = update.version.toString()
        if (store.current()?.releaseVersion == release) return
        val apkId = update.apkAssetId ?: return skip(release, "the release publishes no single APK")
        val infoId = update.clientInfoAssetId ?: return skip(release, "the release publishes no android-client.json")
        val checksumId = update.checksumAssetId ?: return skip(release, "the release publishes no SHA256SUMS")

        val downloaded = File(store.workDir, "downloaded-$release.apk")
        try {
            val info = json.decodeFromString(ClientBuildInfo.serializer(), text(releases.downloadAsset(repo, token, infoId)))
            val checksums = text(releases.downloadAsset(repo, token, checksumId))
            progress?.start(UpdateDto.DownloadPhase.ANDROID)
            val digest = releases.downloadAssetTo(repo, token, apkId, downloaded) { done, total -> progress?.report(done, total) }
            store.stage(release, info, update.releaseNotes, downloaded, digest, checksums)
            log.info("Android client {} (versionCode {}) staged for tills", release, info.versionCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Update {}: the Android client could not be staged: {}", release, e.toString())
        } finally {
            // Only a failure leaves anything behind; a successful stage moved the file away.
            downloaded.delete()
        }
    }

    private fun skip(
        release: String,
        reason: String,
    ) = log.info("Update {}: no Android client staged -- {}", release, reason)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)
}
