package org.example.project.core.network

/**
 * Android: 5-minute pool and up to 3 retries, sized for network drops caused by Doze mode,
 * screen-off, and airplane mode.
 */
actual fun platformHttpClientConfig(): HttpClientConfig = HttpClientConfig.ANDROID
