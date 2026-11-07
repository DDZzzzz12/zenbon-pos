package org.example.project.admin.core.util

import kotlinx.browser.window

/**
 * Resolves a product's [imageUrl] (as returned by the server, typically a relative
 * "api/products/images/{file}") to an ABSOLUTE same-origin URL that Coil's wasm network
 * fetcher can load. Unlike the browser's own `fetch`/`<img>`, Coil's Ktor fetcher does not
 * resolve a relative path against the page origin, so we prepend `window.location.origin`.
 * The admin panel is served same-origin as pos-server (dev: webpack proxies /api).
 */
fun resolveImageUrl(imageUrl: String?): String? {
    val url = imageUrl?.takeIf { it.isNotBlank() } ?: return null
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val path = if (url.startsWith("/")) url else "/$url"
    return window.location.origin + path
}
