package org.example.project.core.data.database

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.utils.platformIoDispatcher

private val log = Logger.withTag("DeviceTokenLoader")

/**
 * Makes [localDb] the place [DeviceTokenProvider] reads the device token from while it holds none.
 *
 * `LocalDatabaseManager` reads nothing when it is built — Koin first resolves it on the UI thread, and the token is
 * sealed. The read happens here instead, when a request needs the token, on [ioDispatcher]. A read the key store
 * cannot serve yet answers null and the provider asks again after its retry interval; the sealed value stays on disk.
 *
 * Installed by the Koin definition of [LocalDatabaseManager]. That puts it in place before the first request only because
 * the `HttpClient` Koin definition resolves `LocalDatabaseManager` eagerly (`get<LocalDatabaseManager>()::getApiBaseUrl`
 * in `NetworkModule`), so building the client installs the loader.
 */
fun installDeviceTokenLoader(
    localDb: LocalDatabaseManager,
    ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    DeviceTokenProvider.loader = { readDeviceToken(localDb, ioDispatcher) }
}

private suspend fun readDeviceToken(
    localDb: LocalDatabaseManager,
    ioDispatcher: CoroutineDispatcher,
): String? =
    try {
        withContext(ioDispatcher) { localDb.getDeviceToken() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Could not read the device token; requests go without it until a later read succeeds" }
        null
    }
