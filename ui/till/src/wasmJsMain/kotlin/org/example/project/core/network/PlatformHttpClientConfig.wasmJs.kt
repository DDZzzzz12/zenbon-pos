package org.example.project.core.network

/**
 * Browser: the fetch engine owns TLS, timeouts, retries and the connection pool, so
 * [HttpClientConfig.WASM]'s numbers are inert and present only so the platform can name itself.
 */
actual fun platformHttpClientConfig(): HttpClientConfig = HttpClientConfig.WASM
