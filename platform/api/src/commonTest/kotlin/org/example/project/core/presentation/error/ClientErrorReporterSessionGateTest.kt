package org.example.project.core.presentation.error

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NEW-23 / FO-36: adminApp reported every shown error, and signed out it has no credential, so each
 * failed sign-in also posted `POST /api/v1/client-errors` and earned a 401. The reporter now drops a
 * report while its `mayReport` gate is closed and keeps reporting once it opens.
 */
class ClientErrorReporterSessionGateTest {
    private var posts = 0

    private fun reporter(
        scope: TestScope,
        signedIn: () -> Boolean,
    ): DefaultClientErrorReporter {
        // The engine runs on the test's own scheduler, so advanceUntilIdle() really waits for the post.
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(scope.testScheduler)
                    addHandler { _ ->
                        posts++
                        respond(
                            content = """{"clientErrorId":"x","logged":true}""",
                            status = HttpStatusCode.Accepted,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                },
            )
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return DefaultClientErrorReporter(
            httpCall =
                object : HttpCall {
                    override val httpClient: HttpClient = http

                    override fun getBaseUrl(): String = "http://localhost/"
                },
            scope = scope,
            app = "ADMIN",
            appVersion = "test",
            employeeNumberProvider = { null },
            terminalIdProvider = { null },
            mayReport = signedIn,
        )
    }

    private val error = UiError.Network()

    @Test
    fun signedOut_nothingIsPosted() =
        runTest(StandardTestDispatcher()) {
            reporter(this) { false }.report(error, "Anmeldung", "Server nicht erreichbar", screen = "login")
            advanceUntilIdle()

            assertEquals(0, posts)
        }

    @Test
    fun signedIn_theErrorIsPosted() =
        runTest(StandardTestDispatcher()) {
            reporter(this) { true }.report(error, "Belege", "Server nicht erreichbar", screen = "documents")
            advanceUntilIdle()

            assertEquals(1, posts)
        }

    @Test
    fun anErrorDroppedWhileSignedOut_isStillReportedAfterSignIn() =
        runTest(StandardTestDispatcher()) {
            var signedIn = false
            val reporter = reporter(this) { signedIn }
            reporter.report(error, "Anmeldung", "Server nicht erreichbar", screen = "login")
            advanceUntilIdle()

            signedIn = true
            reporter.report(error, "Anmeldung", "Server nicht erreichbar", screen = "login")
            advanceUntilIdle()

            assertEquals(1, posts, "the drop must not use up the de-duplication slot")
        }
}
