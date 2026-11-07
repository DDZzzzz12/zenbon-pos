package org.example.project.core.network

/**
 * Desktop: 10-minute keep-alive pool and up to 5 retries. A POS terminal cannot afford
 * downtime during a transaction, so WiFi drops and server restarts must self-heal.
 */
actual fun platformHttpClientConfig(): HttpClientConfig = HttpClientConfig.DESKTOP
