package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import org.example.project.core.browser.SingleTabGate
import org.example.project.core.network.createPlatformEngine
import org.example.project.core.network.platformHttpClientConfig
import org.example.project.features.auth.presentation.navigation.AppNavigation

@Composable
fun App() {
    // Without this Coil builds its own default Ktor client, which ignores OsTrustStore and would
    // fail every product image on a shop running Caddy's local CA.
    // No ServerUrlGuard here on purpose: this client sends no token or credentials, and a product's
    // imageUrl may be an absolute http:// URL on another host (Product.resolveImageUrl). Desktop
    // still loads those; Android's network security config blocks cleartext to non-loopback hosts.
    setSingletonImageLoaderFactory { context ->
        ImageLoader
            .Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { HttpClient(createPlatformEngine(platformHttpClientConfig())) }))
            }.build()
    }
    // Android draws edge-to-edge and nothing below consumes the insets, so the gesture handle sat
    // on top of the bottom-most row — the TSE banner on a phone, the status bar on a till
    // (E2E-2026-09-19). Only the navigation bars are inset: the app's own top bars already clear
    // the status bar. On desktop and wasm the inset is empty, so this is a no-op there.
    // A browser station runs in exactly one tab; a duplicate tab gets a notice instead of the app.
    SingleTabGate {
        Box(modifier = Modifier.navigationBarsPadding()) {
            AppNavigation()
        }
    }
}
