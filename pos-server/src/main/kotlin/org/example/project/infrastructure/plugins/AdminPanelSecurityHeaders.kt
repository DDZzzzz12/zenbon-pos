package org.example.project.infrastructure.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.StaticContentConfig
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import java.net.URL

/**
 * Content-Security-Policy for the two browser apps pos-server serves, /admin/ and /pos-preview/.
 *
 * Every source here is one the panel is known to load: adminApp.js and its wasm (script-src with
 * 'wasm-unsafe-eval'), the inline <style> in index.html (style-src 'unsafe-inline' — scripts stay
 * strict), Coil's image-decoding worker, spun up from a `blob:` URL for every product thumbnail,
 * shop logo and product-edit image on wasm (worker-src) — without it the browser falls back to
 * script-src, which has no `blob:` source, and every image in the panel silently fails to render.
 * Coil's Ktor fetcher pulls the image bytes themselves over /api (connect-src); img-src only
 * covers favicons and other bytes the browser fetches directly. Fonts are likewise fetched as
 * bytes and handed to Compose/Skia (connect-src); font-src is kept as a second line of defense in
 * case that ever changes. The PDF viewer opens its document in a `blob:` iframe (frame-src), and
 * the injected <base href="/admin/"> needs base-uri 'self'. Anything else — an injected <script>,
 * a form posting elsewhere, framing by another site — is refused by the browser.
 *
 * `object-src 'none'` blocks `<object>`/`<embed>` plugin content. A real-browser pass on
 * 2026-09-13 confirmed the PDF viewer renders correctly inside the `blob:` iframe in Chrome under
 * `object-src 'none'` — if a future browser's PDF viewer instead renders via an `<object>`/
 * `<embed>` inside that iframe (rather than the iframe's own document context), a blank viewer
 * with no console error is the symptom, and the fix is `object-src blob:`.
 *
 * Change this only after checking the panel in a real browser: a missing source does not fail the
 * build, it fails silently as a blank canvas (or a blank image, or a blank PDF) with a CSP error
 * in the console.
 */
internal const val ADMIN_PANEL_CSP =
    "default-src 'self'; " +
        "script-src 'self' 'wasm-unsafe-eval'; " +
        "worker-src 'self' blob:; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; " +
        "font-src 'self' data:; " +
        "connect-src 'self'; " +
        "frame-src blob:; " +
        "object-src 'none'; " +
        "base-uri 'self'; " +
        "form-action 'self'; " +
        "frame-ancestors 'none'"

private val BROWSER_APP_ROOTS = listOf("/admin", "/pos-preview")

/**
 * Appends the CSP + `X-Content-Type-Options` + `Referrer-Policy` headers used by the two browser
 * apps, unless a `Content-Security-Policy` is already on the response.
 *
 * There are two layers that can call this for the same request:
 * 1. [browserAppStaticResources]'s `modify` hook — runs once a static resource under /admin or
 *    /pos-preview is actually matched, keyed off the *decoded, routed* path. This is the
 *    authoritative layer: Ktor 3.5.1's routing decodes percent-escapes and collapses empty path
 *    segments before matching, so `GET /%61dmin/index.html` and `GET //admin/index.html` are both
 *    routed to the `/admin` static route and get headers here, even though their raw request path
 *    doesn't textually start with `/admin/`.
 * 2. [AdminPanelSecurityHeaders]'s `onCall` hook — runs earlier, against the raw request path
 *    (`call.request.path()`, undecoded). It covers requests the static route never sees at all —
 *    a 404 for a path under the prefix that matches no resource — where there is no static-route
 *    `modify` hook to fall back on.
 *
 * The "already present" guard is what lets both layers be installed without double-appending a
 * header on the common case where a request path is textually clean (`GET /admin/index.html`):
 * the `onCall` layer runs first and sets the headers, and the static route's `modify` sees them
 * already there and does nothing.
 */
internal fun ApplicationCall.appendBrowserAppSecurityHeaders() {
    if (response.headers["Content-Security-Policy"] != null) return
    response.headers.append("Content-Security-Policy", ADMIN_PANEL_CSP)
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
}

/**
 * [staticResources] for one of the two browser apps, with [appendBrowserAppSecurityHeaders]
 * wired in as the `modify` hook so a served resource always carries the security headers — see
 * that function's KDoc for why this is the authoritative layer and how it relates to
 * [AdminPanelSecurityHeaders].
 *
 * Note: [StaticContentConfig.modify] replaces any previous hook rather than composing with it —
 * a `modify { ... }` set inside [configure] is silently overwritten by the security-header hook
 * installed here. Callers must not set `modify` inside `configure`.
 */
internal fun Route.browserAppStaticResources(
    remotePath: String,
    basePackage: String,
    configure: StaticContentConfig<URL>.() -> Unit = {},
): Route =
    staticResources(remotePath, basePackage) {
        configure()
        // Serves the .br/.gz siblings written at packaging time (precompress.mjs); a file without
        // one (dev run, tests, small files) is served as before and left to the Compression plugin.
        preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
        etag { resource -> StaticResourceValidators.etagOf(resource as URL) }
        modify { _, call -> call.appendBrowserAppSecurityHeaders() }
    }

/**
 * Second, redundant layer of the two browser apps' security headers — see
 * [appendBrowserAppSecurityHeaders] for why it exists alongside [browserAppStaticResources] and
 * why the two never double-append.
 */
val AdminPanelSecurityHeaders =
    createApplicationPlugin("AdminPanelSecurityHeaders") {
        onCall { call ->
            val path = call.request.path()
            val isBrowserApp = BROWSER_APP_ROOTS.any { root -> path == root || path.startsWith("$root/") }
            if (!isBrowserApp) return@onCall
            call.appendBrowserAppSecurityHeaders()
        }
    }
