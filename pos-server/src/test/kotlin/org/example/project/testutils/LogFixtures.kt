package org.example.project.testutils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lines in the exact formats logback.xml writes, for parser and search tests. */
object LogFixtures {
    /** A LogstashEncoder line (application.log, rejected.log). MDC values are strings, as logback writes them. */
    fun jsonLine(
        timestamp: String,
        message: String,
        level: String = "INFO",
        logger: String = "rejected",
        traceId: String? = null,
        code: String? = null,
        status: Int? = null,
        method: String? = null,
        path: String? = null,
        durationMs: Long? = null,
        employeeId: String? = null,
        deviceId: String? = null,
        stackTrace: String? = null,
    ): String =
        buildJsonObject {
            put("@timestamp", timestamp)
            put("@version", "1")
            put("message", message)
            put("logger_name", logger)
            put("thread_name", "eventLoopGroupProxy-4-1")
            put("level", level)
            put("level_value", 20000)
            traceId?.let { put("traceId", it) }
            code?.let { put("code", it) }
            status?.let { put("statusCode", it.toString()) }
            employeeId?.let { put("employeeId", it) }
            deviceId?.let { put("deviceId", it) }
            method?.let { put("method", it) }
            path?.let { put("path", it) }
            durationMs?.let { put("duration_ms", it.toString()) }
            stackTrace?.let { put("stack_trace", it) }
        }.toString()

    /** error.log: `%d [traceId] [code] [statusCode] %logger - %msg%n%ex` */
    fun errorLogEntry(
        timestamp: String,
        traceId: String = "",
        code: String = "",
        status: String = "",
        logger: String = "StatusPages",
        message: String,
        stackLines: List<String> = emptyList(),
    ): List<String> = listOf("$timestamp [$traceId] [$code] [$status] $logger - $message") + stackLines

    /** client-error.log header plus the exceptionType / exceptionMessage / stackTrace block. */
    fun clientErrorEntry(
        timestamp: String,
        message: String,
        clientApp: String = "admin",
        clientErrorId: String = "c-1",
        traceId: String = "",
        code: String = "",
        httpStatus: String = "",
        screen: String = "Orders",
        employee: String = "1042",
        device: String = "dev-1",
        version: String = "1.1.0",
        exceptionType: String = "",
        exceptionMessage: String = "",
        stackLines: List<String> = emptyList(),
    ): List<String> =
        listOf(
            "$timestamp [$clientApp] [$clientErrorId] [$traceId] [$code] [$httpStatus] " +
                "screen=$screen employee=$employee device=$device version=$version - $message",
            exceptionType,
            exceptionMessage,
        ) + stackLines + listOf("")
}
