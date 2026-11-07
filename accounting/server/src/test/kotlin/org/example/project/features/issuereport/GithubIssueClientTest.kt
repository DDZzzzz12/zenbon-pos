package org.example.project.features.issuereport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.example.project.features.issuereport.data.remote.GithubIssueClient
import org.example.project.features.issuereport.domain.models.IssueCreateResult
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("GithubIssueClient")
class GithubIssueClientTest {
    private val repo = GithubReleaseClient.Repo("https://api.github.com", "DDZzzzz12", "POS-KMP")

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): GithubIssueClient =
        GithubIssueClient(HttpClient(MockEngine(handler)))

    @Test
    fun `posts title and body with the bearer token and parses the created issue`() =
        runTest {
            var seen: HttpRequestData? = null
            var sentBody = ""
            val sut =
                client { request ->
                    seen = request
                    sentBody = String(request.body.toByteArray())
                    respond(
                        """{"number":42,"html_url":"https://github.com/DDZzzzz12/POS-KMP/issues/42"}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val result = sut.create(repo, "tok", "T", "B")

            assertEquals(IssueCreateResult.Created(42, "https://github.com/DDZzzzz12/POS-KMP/issues/42"), result)
            assertEquals(HttpMethod.Post, seen!!.method)
            assertEquals("/repos/DDZzzzz12/POS-KMP/issues", seen!!.url.encodedPath)
            assertEquals("Bearer tok", seen!!.headers[HttpHeaders.Authorization])
            assertTrue(sentBody.contains("\"title\":\"T\"") && sentBody.contains("\"body\":\"B\""), sentBody)
        }

    @Test
    fun `401 403 and 404 with a token are TokenRejected`() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
                val result = client { respond("{}", status) }.create(repo, "tok", "T", "B")
                assertEquals(IssueCreateResult.TokenRejected, result, "status $status")
            }
        }

    @Test
    fun `429 and an exhausted 403 are RateLimited`() =
        runTest {
            val tooMany = client { respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30")) }
            assertEquals(IssueCreateResult.RateLimited(30), tooMany.create(repo, "tok", "T", "B"))

            val exhausted = client { respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining", "0")) }
            assertEquals(IssueCreateResult.RateLimited(null), exhausted.create(repo, "tok", "T", "B"))
        }

    @Test
    fun `a transport failure is Unreachable and other statuses carry the code`() =
        runTest {
            val boom = client { throw IOException("connection reset to https://api.github.com?token=tok") }
            val unreachable = boom.create(repo, "tok", "T", "B") as IssueCreateResult.Unreachable
            // Class name only: a transport message can echo the URL, headers or the token.
            assertEquals("IOException", unreachable.reason)

            val gone = client { respond("{}", HttpStatusCode.Gone) }
            val result = gone.create(repo, "tok", "T", "B") as IssueCreateResult.Unreachable
            assertTrue(result.reason.contains("410"), result.reason)
        }

    @Test
    fun `a 201 with an unreadable body is Unreachable not a crash`() =
        runTest {
            val result = client { respond("not json", HttpStatusCode.Created) }.create(repo, "tok", "T", "B")
            assertTrue(result is IssueCreateResult.Unreachable)
        }

    @Test
    fun `a 302 is not followed and the token is sent exactly once`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.host == "api.github.com") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/collect"))
                    } else {
                        respond("""{"number":1,"html_url":"https://evil.example/1"}""", HttpStatusCode.Created)
                    }
                }
            val result = GithubIssueClient(HttpClient(engine)).create(repo, "tok", "T", "B")

            assertEquals(1, engine.requestHistory.size, "requests: ${engine.requestHistory.map { it.url }}")
            val only = engine.requestHistory.single()
            assertEquals("api.github.com", only.url.host)
            assertEquals(0, engine.requestHistory.count { it.url.host != "api.github.com" && it.headers[HttpHeaders.Authorization] != null })
            val unreachable = result as IssueCreateResult.Unreachable
            assertTrue(unreachable.reason.contains("302"), unreachable.reason)
        }
}
