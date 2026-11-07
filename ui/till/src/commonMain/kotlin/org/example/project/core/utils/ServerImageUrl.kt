package org.example.project.core.utils

/**
 * Joins the configured API base URL and a server-relative image path ("api/shop-info/logo/x.jpg",
 * product image paths). The server stores these without a leading slash, and the configured base
 * URL may or may not end in one — joining by plain concatenation only works for one of the two,
 * which is how the shop logo went missing on the dashboard. Absolute (`http(s)://`) and `file://`
 * URLs pass through untouched. Returns null when there is nothing to load.
 */
fun resolveServerImageUrl(
    baseUrl: String?,
    path: String?,
): String? {
    val trimmed = path?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("file://") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val base = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    return "$base/${trimmed.trimStart('/')}"
}
