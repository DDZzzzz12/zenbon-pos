package org.example.project.infrastructure.errors

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.plugins.configureCallId
import org.example.project.infrastructure.plugins.configureCallLogging
import org.example.project.infrastructure.plugins.configureStatusPages
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorLoggingTest {
    @TempDir
    lateinit var tempDir: Path

    private var attachedAppender: FileAppender<ILoggingEvent>? = null

    @AfterTest
    fun detachAppender() {
        val a = attachedAppender ?: return
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.detachAppender(a)
        a.stop()
        attachedAppender = null
    }

    private fun installErrorAppender(): FileAppender<ILoggingEvent> {
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        val pattern =
            PatternLayoutEncoder().apply {
                context = ctx
                pattern = "[%X{traceId:-}] [%X{code:-}] [%X{statusCode:-}] %level %logger %msg%n"
                start()
            }
        val appender =
            FileAppender<ILoggingEvent>().apply {
                this.context = ctx
                file = tempDir.resolve("error.log").toString()
                encoder = pattern
                addFilter(
                    ThresholdFilter().apply {
                        this.context = ctx
                        setLevel(Level.ERROR.toString())
                        start()
                    },
                )
                start()
            }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        attachedAppender = appender
        return appender
    }

    /**
     * Probes a 5xx deliberately. This test used to throw a 404, but a 4xx no longer reaches
     * error.log at all (E2E-2026-09-15 N12), so a client-correctable status can no longer prove
     * that the MDC keys land in the line prefix — which is what this test exists for. The 404's
     * *absence* is pinned by `a 4xx stays out of the error log while a 5xx still reaches it`.
     */
    @Test
    fun `error log captures a 5xx PosException with traceId and code`() =
        testApplication {
            val appender = installErrorAppender()
            application {
                configureCallId()
                configureCallLogging()
                configureStatusPages()
                routing {
                    get("/probe") {
                        throw PosException(PosErrorCode.INTERNAL_ERROR, HttpStatusCode.InternalServerError, "x")
                    }
                }
            }
            val client: HttpClient = createClient { expectSuccess = false }
            val resp = client.get("/probe")
            appender.stop() // flush

            val log = tempDir.resolve("error.log").toFile().readText()
            assertTrue(log.contains("INTERNAL_ERROR"), "expected error.log to contain code; got:\n$log")

            val traceId = resp.headers["X-Request-ID"] ?: error("no X-Request-ID")
            assertTrue(log.contains(traceId), "expected error.log to contain traceId=$traceId; got:\n$log")
            assertTrue(log.contains("500"), "expected error.log to contain status 500; got:\n$log")
        }

    @Test
    fun `info-level logs do not appear in error log`() =
        testApplication {
            val appender = installErrorAppender()
            application {
                configureCallId()
                configureCallLogging()
                configureStatusPages()
                routing {
                    get("/info") {
                        LoggerFactory.getLogger("InfoProbe").info("info-only-line-marker-xyz")
                        call.respondText("ok")
                    }
                }
            }
            val client: HttpClient = createClient { expectSuccess = false }
            client.get("/info")
            appender.stop()

            val logFile = tempDir.resolve("error.log").toFile()
            if (logFile.exists()) {
                assertFalse(
                    logFile.readText().contains("info-only-line-marker-xyz"),
                    "info-level message must not appear in error.log",
                )
            }
        }

    /**
     * A 4xx is an expected, client-correctable outcome, not an operator-visible failure: the
     * 2026-09-15 e2e run put 34 expired-bearer 401s into logs/error.log, where each one reads as a
     * fault even though the 401 is precisely what drives the client's refresh flow. Only 5xx —
     * something the server got wrong — belongs there (E2E-2026-09-15 N12).
     */
    @Test
    fun `a 4xx stays out of the error log while a 5xx still reaches it`() =
        testApplication {
            val appender = installErrorAppender()
            application {
                configureCallId()
                configureCallLogging()
                configureStatusPages()
                routing {
                    get("/not-found") {
                        throw PosException(PosErrorCode.EMPLOYEE_NOT_FOUND, HttpStatusCode.NotFound, "no HR record")
                    }
                    get("/boom") { throw RuntimeException("boom") }
                }
            }
            val client: HttpClient = createClient { expectSuccess = false }
            client.get("/not-found")
            client.get("/boom")
            appender.stop() // flush

            val log = tempDir.resolve("error.log").toFile().readText()
            assertFalse(
                log.contains("EMPLOYEE_NOT_FOUND"),
                "a 404 is client-correctable and must not reach error.log; got:\n$log",
            )
            assertTrue(
                log.contains("INTERNAL_ERROR"),
                "a 500 is an operator-visible failure and must still reach error.log; got:\n$log",
            )
        }

    /**
     * The expired-bearer case from the e2e run reaches [configureStatusPages] through the
     * `status(Unauthorized)` interceptor rather than a thrown exception, so it carries no `cause`.
     * That is the arm the 34 ERROR lines came from, and it needs its own probe.
     */
    @Test
    fun `an unauthorized answered by the status interceptor stays out of the error log`() =
        testApplication {
            val appender = installErrorAppender()
            application {
                configureCallId()
                configureCallLogging()
                install(Authentication) {
                    jwt("auth-jwt") {
                        realm = "test"
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                configureStatusPages()
                routing {
                    authenticate("auth-jwt") {
                        get("/secure") { call.respond("ok") }
                    }
                }
            }
            val client: HttpClient = createClient { expectSuccess = false }
            client.get("/secure")
            appender.stop()

            val logFile = tempDir.resolve("error.log").toFile()
            if (logFile.exists()) {
                assertFalse(
                    logFile.readText().contains("AUTH_INVALID_CREDENTIALS"),
                    "a 401 drives the client's refresh flow and must not reach error.log",
                )
            }
        }
}
