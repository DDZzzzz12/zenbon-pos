package org.example.project.infrastructure.plugins

import io.ktor.http.content.LastModifiedVersion
import io.ktor.http.content.versions
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.path

/**
 * Makes the content-hash `ETag` the only conditional-request validator for the browser apps
 * (`/admin`, `/pos`).
 *
 * A JAR entry carries the reproducible-build timestamp (1980), so the `Last-Modified` Ktor derives
 * from it never changes. `ConditionalHeaders` returns the first non-OK version check, so a browser's
 * `If-Modified-Since` made it answer 304 even when its `If-None-Match` was stale. The browser then kept
 * a cached `composeApp.js` from the previous release whose chunk and wasm names no longer exist --
 * a blank page after an upgrade. Dropping the [LastModifiedVersion] leaves only the ETag, which is
 * correct (RFC 9110: `If-None-Match` takes precedence over `If-Modified-Since`).
 *
 * Install **before** `ConditionalHeaders`: hooks run in install order.
 */
val BrowserAppEtagOnly =
    createApplicationPlugin("BrowserAppEtagOnly") {
        on(ResponseBodyReadyForSend) { call, content ->
            val path = call.request.path()
            val isBrowserApp = BROWSER_APP_PREFIXES.any { path == it || path.startsWith("$it/") }
            if (isBrowserApp) content.versions = content.versions.filterNot { it is LastModifiedVersion }
        }
    }

private val BROWSER_APP_PREFIXES = listOf("/admin", "/pos")
