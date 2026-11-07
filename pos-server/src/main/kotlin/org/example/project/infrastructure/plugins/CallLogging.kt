package org.example.project.infrastructure.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import org.slf4j.MDC
import org.slf4j.event.Level

private val RequestStartTimeKey = AttributeKey<Long>("RequestStartTime")
private val DecodedJwtKey = AttributeKey<DecodedJWT>("DecodedJwt")

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.DEBUG

        // traceId comes from CallId (configureCallId()) via the built-in callIdMdc helper.
        installTraceIdMdc()

        // mdc { } lambdas run at request start, before the call is handled.
        // Anything that needs end-of-call data (e.g. duration) belongs in format { }.
        mdc("method") { call ->
            // Side effect: also stash request start time on the call so format { } can
            // compute duration without a separate Monitoring interceptor.
            call.attributes.put(RequestStartTimeKey, System.currentTimeMillis())
            call.request.httpMethod.value
        }
        mdc("path") { call -> call.request.path() }
        mdc("employeeId") { call -> decodedJwt(call)?.subject?.takeIf { it.isNotEmpty() } }
        mdc("deviceId") { call ->
            decodedJwt(call)?.getClaim("deviceId")?.asString()?.takeIf { it.isNotEmpty() }
        }

        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val uri = call.request.uri
            val contentLength = call.response.headers["Content-Length"] ?: "unknown"
            val startTime = call.attributes.getOrNull(RequestStartTimeKey)
            val durationMs = startTime?.let { System.currentTimeMillis() - it }
            val duration = durationMs?.let { "${it}ms" } ?: "?"

            if (durationMs != null) MDC.put("duration_ms", durationMs.toString())
            val message = "📤 RESPONSE: $httpMethod $uri → $status | $duration | Content-Length: $contentLength"
            if (durationMs != null) MDC.remove("duration_ms")
            message
        }

        filter { call -> !isNoisyPath(call.request.path()) }
    }
}

private fun decodedJwt(call: ApplicationCall): DecodedJWT? {
    call.attributes.getOrNull(DecodedJwtKey)?.let { return it }
    val authHeader = call.request.headers["Authorization"] ?: return null
    if (!authHeader.startsWith("Bearer ")) return null
    return try {
        val decoded = JWT.decode(authHeader.removePrefix("Bearer "))
        call.attributes.put(DecodedJwtKey, decoded)
        decoded
    } catch (_: Exception) {
        null
    }
}

private fun isNoisyPath(path: String): Boolean =
    path.startsWith("/health") ||
        path.startsWith("/openapi") ||
        path.startsWith("/swagger") ||
        path.startsWith("/static") ||
        path.contains("/sync/") ||
        path.startsWith("/ws/")
