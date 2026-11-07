package org.example.project.features.issuereport.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.example.project.features.issuereport.domain.models.IssueCreateResult
import org.example.project.features.issuereport.domain.tracker.IssueTracker
import org.example.project.features.update.data.remote.GithubReleaseClient
import org.slf4j.LoggerFactory

/**
 * Creates one GitHub issue. Deliberately separate from [GithubReleaseClient], which reads releases
 * and carries redirect-walking logic that has nothing to do with creating an issue.
 *
 * GitHub answers 404 (not 403) when a token cannot see a repository, so a 404 seen *with* a token
 * means "this credential no longer opens that repository" — [IssueCreateResult.TokenRejected].
 * The token and the body are never logged.
 */
class GithubIssueClient(
    client: HttpClient,
) : IssueTracker {
    private val log = LoggerFactory.getLogger(GithubIssueClient::class.java)
    private val noRedirect: HttpClient = client.config { followRedirects = false }

    override suspend fun create(
        repo: GithubReleaseClient.Repo,
        token: String,
        title: String,
        body: String,
    ): IssueCreateResult {
        val payload =
            buildJsonObject {
                put("title", title)
                put("body", body)
            }.toString()
        val response =
            try {
                noRedirect.post("${repo.baseUrl.trimEnd('/')}/repos/${repo.owner}/${repo.name}/issues") {
                    accept(ContentType.Application.Json)
                    header(HEADER_API_VERSION, API_VERSION)
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: a transport exception's message can echo request details.
                val kind = e::class.simpleName ?: "transport failure"
                log.warn("Issue creation could not reach GitHub: {}", kind)
                return IssueCreateResult.Unreachable(kind)
            }

        val status = response.status
        return when {
            status.isSuccess() -> parseCreated(response.bodyAsText())
            status == HttpStatusCode.TooManyRequests ->
                IssueCreateResult.RateLimited(response.headers["Retry-After"]?.toLongOrNull())
            status == HttpStatusCode.Forbidden && response.headers["x-ratelimit-remaining"] == "0" ->
                IssueCreateResult.RateLimited(response.headers["Retry-After"]?.toLongOrNull())
            status == HttpStatusCode.Unauthorized ||
                status == HttpStatusCode.Forbidden ||
                status == HttpStatusCode.NotFound -> IssueCreateResult.TokenRejected
            else -> IssueCreateResult.Unreachable("GitHub answered HTTP ${status.value}")
        }
    }

    private fun parseCreated(text: String): IssueCreateResult =
        try {
            val json: JsonObject = Json.parseToJsonElement(text).jsonObject
            IssueCreateResult.Created(
                number = json.getValue("number").jsonPrimitive.long,
                url = json.getValue("html_url").jsonPrimitive.content,
            )
        } catch (e: Exception) {
            IssueCreateResult.Unreachable("malformed GitHub response: ${e::class.simpleName}")
        }

    private companion object {
        private const val HEADER_API_VERSION = "X-GitHub-Api-Version"
        private const val API_VERSION = "2022-11-28"
    }
}
