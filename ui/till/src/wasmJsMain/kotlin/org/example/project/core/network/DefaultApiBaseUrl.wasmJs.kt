package org.example.project.core.network

import kotlinx.browser.window

/**
 * The page's own origin — the server serving this page IS the API.
 *
 * pos-server mounts the browser till at `/pos` on the same origin as `/api`, deliberately, so
 * that no CORS plugin is needed and the page cannot become a way for an arbitrary origin to
 * reach the API. Defaulting to the origin makes that arrangement work by construction instead of by
 * coincidence, and it is what lets the webpack dev server work too: the page is then asking its own
 * origin, which the dev server proxies to pos-server.
 */
actual fun defaultApiBaseUrl(): String = window.location.origin.trimEnd('/') + "/"
