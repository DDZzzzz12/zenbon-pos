package org.example.project.core.network

import io.ktor.client.plugins.logging.LogLevel

/**
 * The Ktor [LogLevel] for the till's HTTP client.
 *
 * [LogLevel.ALL] logs request and response BODIES, and the `Logging` plugin sanitizes headers only —
 * so it writes employee passwords, login secrets and access/refresh tokens verbatim to logcat (Android)
 * or stdout (desktop). Bodies are therefore logged only when [logBodies] is set, which the app wires to
 * `BuildKonfig.HTTP_LOG_BODIES` (`-Ppos.httpLogBodies=true`, off by default). Otherwise
 * [LogLevel.INFO]: method, URL and status, no headers, no body.
 */
fun httpLogLevel(logBodies: Boolean): LogLevel = if (logBodies) LogLevel.ALL else LogLevel.INFO
