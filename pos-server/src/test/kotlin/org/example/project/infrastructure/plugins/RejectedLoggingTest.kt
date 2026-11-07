package org.example.project.infrastructure.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.testApp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A 4xx used to be logged at DEBUG, i.e. never, so "why was this refused?" had no answer on the
 * server. It now goes to the dedicated `rejected` logger (-> rejected.log), with `traceId`, `method`
 * and `path` set explicitly because the refusal may be raised before CallLogging has filled the MDC.
 */
class RejectedLoggingTest {
    private val rejectedLogger = LoggerFactory.getLogger("rejected") as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun attach() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        rejectedLogger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        rejectedLogger.detachAppender(appender)
    }

    private fun bareApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureCallId()
                configureStatusPages()
                routing {
                    get("/bad") {
                        throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "Employee 1042 sent a bad value")
                    }
                    get("/forbidden") { call.respond(HttpStatusCode.Forbidden) }
                    get("/boom") { error("kaboom") }
                }
            }
            block()
        }

    @Test
    fun `a 4xx PosException is logged with code, status, reason and an explicit MDC`() =
        bareApp {
            val response = client.get("/bad") { header(TRACE_ID_HEADER, "trace-4xx-1") }
            assertEquals(HttpStatusCode.BadRequest, response.status)

            val event = appender.list.single()
            assertEquals("INFO", event.level.toString())
            assertEquals("VALIDATION_FAILED 400 — Employee 1042 sent a bad value", event.formattedMessage)
            assertEquals("trace-4xx-1", event.mdcPropertyMap["traceId"])
            assertEquals("GET", event.mdcPropertyMap["method"])
            assertEquals("/bad", event.mdcPropertyMap["path"])
            assertEquals("VALIDATION_FAILED", event.mdcPropertyMap["code"])
            assertEquals("400", event.mdcPropertyMap["statusCode"])
        }

    @Test
    fun `a bare status refusal with no exception is logged too`() =
        bareApp {
            val response = client.get("/forbidden") { header(TRACE_ID_HEADER, "trace-4xx-2") }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            val event = appender.list.single()
            assertEquals("AUTH_FORBIDDEN 403", event.formattedMessage)
            assertEquals("trace-4xx-2", event.mdcPropertyMap["traceId"])
        }

    @Test
    fun `a 5xx is not a rejection and stays out of the rejected logger`() =
        bareApp {
            val response = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(appender.list.isEmpty(), "5xx belongs in error.log: ${appender.list.map { it.formattedMessage }}")
        }

    @Test
    fun `a refusal raised before routing still records the trace id`() =
        testApp { client ->
            // No bearer on a capability-gated route: RouteAuthorizationPlugin answers 401 in onCall,
            // before CallLogging (not installed in tests) could have set any MDC.
            val response = client.get("/api/admin/permissions") { header(TRACE_ID_HEADER, "trace-401-pre") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)

            val event = appender.list.single { it.mdcPropertyMap["traceId"] == "trace-401-pre" }
            assertEquals("AUTH_INVALID_CREDENTIALS 401", event.formattedMessage)
            assertEquals("/api/admin/permissions", event.mdcPropertyMap["path"])
        }
}
