package org.example.project.core.network

actual val isPinningSupported: Boolean = false

actual suspend fun fetchUnverifiedRootCa(baseUrl: String): String = throw RootCaFetchException("Certificate pinning is not available in the browser build")
