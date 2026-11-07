package org.example.project.core.appupdate

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.network.createPlatformEngine
import org.example.project.core.network.platformHttpClientConfig
import org.example.project.features.appupdate.domain.AppUpdateException
import org.example.project.features.appupdate.domain.AppUpdateFailure
import org.example.project.features.appupdate.domain.AppUpdatePlatform
import java.io.File
import java.security.MessageDigest

/**
 * Android self-update: streams the APK into app-private cache while hashing it, then installs it
 * through [PackageInstaller]. The OS install confirmation cannot be suppressed.
 *
 * Nothing is written outside `cacheDir/updates/`. A file that does not match the published digest
 * is deleted before this class reports anything.
 */
class AndroidAppUpdatePlatform(
    private val context: Application,
) : AppUpdatePlatform {
    private var downloaded: File? = null

    override fun installedVersionCode(): Long? =
        runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrNull()

    override suspend fun download(
        url: String,
        info: UpdateDto.AndroidClientDto,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        discardDownload()
        val dir = File(context.cacheDir, UPDATE_DIR).also { it.mkdirs() }
        val target = File(dir, "zenbon-pos-${info.versionCode}.apk")
        val client =
            HttpClient(createPlatformEngine(platformHttpClientConfig())) {
                // A multi-megabyte download over shop Wi-Fi: no overall deadline, but a stalled socket still ends.
                install(HttpTimeout) {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
        try {
            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) throw AppUpdateException(AppUpdateFailure.DOWNLOAD_FAILED)
                val headerDigest = response.headers[HEADER_SHA256]
                val actual = writeAndHash(response.bodyAsChannel(), target, info.sizeBytes, onProgress)
                val matchesManifest = actual.equals(info.sha256, ignoreCase = true)
                val matchesHeader = headerDigest == null || actual.equals(headerDigest, ignoreCase = true)
                if (!matchesManifest || !matchesHeader) throw AppUpdateException(AppUpdateFailure.HASH_MISMATCH)
            }
            downloaded = target
        } catch (e: AppUpdateException) {
            target.delete()
            throw e
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            throw AppUpdateException(AppUpdateFailure.DOWNLOAD_FAILED)
        } finally {
            client.close()
        }
    }

    private suspend fun writeAndHash(
        channel: io.ktor.utils.io.ByteReadChannel,
        target: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                done += read
                onProgress(done, total)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun install() =
        withContext(Dispatchers.IO) {
            val apk = downloaded?.takeIf { it.isFile } ?: throw AppUpdateException(AppUpdateFailure.INSTALL_FAILED)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(apk.length()) }
            val sessionId = installer.createSession(params)
            val action = "${context.packageName}.APP_UPDATE_RESULT"
            val verdict = CompletableDeferred<Int>()
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: Intent,
                    ) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            // Android's own confirmation screen. We are in the foreground (the dialog is showing).
                            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                                receiverContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        } else {
                            verdict.complete(status)
                        }
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite(SESSION_ENTRY, 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val pending =
                        PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    session.commit(pending.intentSender)
                }
                // On success Android replaces this process, so this line is normally never reached.
                when (verdict.await()) {
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    PackageInstaller.STATUS_FAILURE_ABORTED -> throw AppUpdateException(AppUpdateFailure.INSTALL_CANCELLED)
                    else -> throw AppUpdateException(AppUpdateFailure.INSTALL_FAILED)
                }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

    override fun discardDownload() {
        downloaded = null
        File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val UPDATE_DIR = "updates"
        const val SESSION_ENTRY = "zenbon-pos.apk"
        const val HEADER_SHA256 = "X-SHA256"
        const val BUFFER_BYTES = 1 shl 16
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 60_000L
    }
}
