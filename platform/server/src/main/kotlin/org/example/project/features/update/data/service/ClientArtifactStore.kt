package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.error.PosErrorCode
import org.example.project.features.update.domain.models.ClientBuildInfo
import org.example.project.features.update.domain.models.StagedAndroidClient
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Holds the one Android APK that tills may download.
 *
 * It lives in its **own directory**, not in [UpdateStager]'s staging directory, for two reasons:
 * `UpdateStager.pruneExcept` sweeps everything but the server artifact, and a till downloads its
 * APK *after* the server has applied the update and restarted -- the one moment the server
 * artifact is consumed.
 *
 * Same invariant as [UpdateStager]: **nothing is written under the final names unless its digest
 * matched the published one.** The unverified download is assembled in [workDir]. The metadata
 * file is deleted before the APK is replaced and written last, so a reader in the window between
 * sees "nothing staged" (404) rather than old metadata describing new bytes; [current] also
 * insists the APK's length equals the recorded size.
 */
class ClientArtifactStore(
    private val dir: File,
    private val stager: UpdateStager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Where an APK is assembled before it verifies. Inside [dir] so the final move is same-filesystem. */
    val workDir: File get() = File(dir, WORK_DIR_NAME)

    /** What is staged, or null when nothing is or what is on disk is incomplete. */
    fun current(): StagedAndroidClient? {
        val meta = File(dir, META_NAME).takeIf { it.isFile } ?: return null
        val staged =
            runCatching { json.decodeFromString(StagedAndroidClient.serializer(), meta.readText()) }.getOrNull()
                ?: return null
        val apk = File(dir, APK_NAME)
        return staged.takeIf { apk.isFile && apk.length() == it.sizeBytes }
    }

    fun apkFile(): File? = current()?.let { File(dir, APK_NAME) }

    /**
     * Verifies [actualDigest] (computed while [downloaded] was written) against the APK line of
     * [sha256sumsBody] and, only on a match, moves [downloaded] into place. **Consumes** [downloaded]
     * on success; on failure it is untouched and the caller deletes it.
     *
     * @throws PosException [PosErrorCode.UPDATE_CHECKSUM_MISMATCH] on any checksum problem.
     */
    fun stage(
        releaseVersion: String,
        info: ClientBuildInfo,
        releaseNotes: String,
        downloaded: File,
        actualDigest: String,
        sha256sumsBody: String,
    ): StagedAndroidClient {
        val expected = stager.expectedApkDigest(releaseVersion, sha256sumsBody)
        if (!actualDigest.equals(expected, ignoreCase = true)) {
            throw PosException(
                PosErrorCode.UPDATE_CHECKSUM_MISMATCH,
                HttpStatusCode.BadGateway,
                "staging the Android client for $releaseVersion refused: checksum mismatch " +
                    "(expected $expected, downloaded $actualDigest)",
            )
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw PosException(
                PosErrorCode.UPDATE_CHECKSUM_MISMATCH,
                HttpStatusCode.BadGateway,
                "staging the Android client for $releaseVersion refused: ${dir.absolutePath} could not be created",
            )
        }

        val staged =
            StagedAndroidClient(
                releaseVersion = releaseVersion,
                versionCode = info.versionCode,
                versionName = info.versionName,
                sha256 = expected.lowercase(),
                sizeBytes = downloaded.length(),
                releaseNotes = releaseNotes,
            )

        File(dir, META_NAME).delete()
        Files.move(downloaded.toPath(), File(dir, APK_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
        val part = File(dir, "$META_NAME.part")
        part.writeText(json.encodeToString(StagedAndroidClient.serializer(), staged))
        Files.move(part.toPath(), File(dir, META_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return staged
    }

    private companion object {
        const val APK_NAME = "android-client.apk"
        const val META_NAME = "android-client.meta.json"
        const val WORK_DIR_NAME = "work"
    }
}
