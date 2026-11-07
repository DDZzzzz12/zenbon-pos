package org.example.project.core.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.sse.SSE

/**
 * Till-only Ktor client plugins layered on top of the shared `createPosHttpClient` via its
 * `extraConfig` hook. Installed by `NetworkModule` for the production client AND by
 * `ServerUrlGuardTest` for its guarded clients, so the test builds a client the same way
 * production does rather than re-declaring the install list.
 *
 * SSE / ContentEncoding / HttpTimeout are composeApp-only: they are meaningless or harmful on a
 * browser engine (adminApp), so they stay out of the shared client (see `PosHttpClient`'s
 * `extraConfig` KDoc). [ServerUrlGuard] refuses any plain-HTTP LAN request before it is sent —
 * every request carries `X-Device-Token`, so adminApp (served same-origin over http on
 * un-reinstalled shops) must not get it.
 */
fun HttpClientConfig<*>.installTillClientPlugins() {
    install(ServerUrlGuard)
    install(SSE)
    install(ContentEncoding) {
        gzip()
        deflate()
        identity()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
}
