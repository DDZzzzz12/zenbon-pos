package org.example.project.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.BuildKonfig
import org.example.project.auth.data.LoginRequest
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's HTTP client must not write request/response bodies to its log in a release build: the
 * `Logging` plugin sanitizes headers only, so a body-level log carries login secrets, employee
 * passwords and access/refresh tokens verbatim into logcat or stdout.
 */
class HttpLogLevelTest {
    @Test
    fun `release configuration logs neither the login secret nor the returned tokens`() =
        runTest {
            val log = loginThroughClientLoggingAt(httpLogLevel(logBodies = false))

            // Non-vacuous: the call itself was logged, so a missing secret means it was withheld.
            assertTrue(log.contains("api/auth/login"), "expected the request line to be logged, got:\n$log")
            assertFalse(log.contains(SECRET), "login secret leaked into the HTTP log:\n$log")
            assertFalse(log.contains(ACCESS_TOKEN), "access token leaked into the HTTP log:\n$log")
            assertFalse(log.contains(REFRESH_TOKEN), "refresh token leaked into the HTTP log:\n$log")
        }

    @Test
    fun `an opted-in dev build logs bodies, so the release assertions can see a leak`() =
        runTest {
            val log = loginThroughClientLoggingAt(httpLogLevel(logBodies = true))

            assertTrue(log.contains(SECRET), "expected the request body in a dev log, got:\n$log")
            assertTrue(log.contains(ACCESS_TOKEN), "expected the response body in a dev log, got:\n$log")
        }

    @Test
    fun `the default build does not opt in to body logging`() {
        assertFalse(
            BuildKonfig.HTTP_LOG_BODIES,
            "HTTP_LOG_BODIES must default to false; enable it only with -Ppos.httpLogBodies=true",
        )
        assertFalse(httpLogLevel(BuildKonfig.HTTP_LOG_BODIES).body)
    }

    private suspend fun loginThroughClientLoggingAt(level: LogLevel): String {
        val log = StringBuilder()
        val capturingLogger =
            object : Logger {
                override fun log(message: String) {
                    log.appendLine(message)
                }
            }
        val engine =
            MockEngine {
                respond(
                    content = """{"token":{"accessToken":"$ACCESS_TOKEN","refreshToken":"$REFRESH_TOKEN","expiresIn":900}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        val client =
            createPosHttpClient(
                engine = engine,
                tokenStore = FakeTokenStore.create(),
                onUnauthorized = { },
                baseUrlResolver = { BASE_URL },
                logger = capturingLogger,
                logLevel = level,
            )
        client
            .post(BASE_URL + "api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(identifier = "1001", secret = SECRET))
            }.bodyAsText()
        client.close()
        return log.toString()
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080/"
        const val SECRET = "pin-secret-4711"
        const val ACCESS_TOKEN = "access-token-eyJhbGciOi"
        const val REFRESH_TOKEN = "refresh-token-d2f81c"
    }
}
