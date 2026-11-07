package org.example.project.admin.core.util

/**
 * Resolves a server path ([path], e.g. a document's `downloadUrl` `"/api/documents/7/file"`) to an
 * absolute URL against the same API base the app's HTTP client uses ([baseUrl], `HttpCall.getBaseUrl()`,
 * which ends in `/` — `"/"` for the panel, served from pos-server's own origin) and the page's [origin].
 *
 * For a fetch that runs outside the Ktor client (the shared `PdfViewer` streams through the browser's
 * `fetch`) and must not depend on the page's `<base href="/admin/">`.
 */
fun resolveApiUrl(
    path: String,
    baseUrl: String,
    origin: String,
): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) baseUrl else origin.trimEnd('/') + "/" + baseUrl.trimStart('/')
    return base.trimEnd('/') + "/" + path.trimStart('/')
}
