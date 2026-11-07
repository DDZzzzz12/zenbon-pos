package org.example.project.core.network

/** Per-platform connection-pool / retry tuning for the shared client engine. */
expect fun platformHttpClientConfig(): HttpClientConfig
