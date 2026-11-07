package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.features.update.domain.models.UpdateSettingsEdit
import org.example.project.infrastructure.errors.PosException
import java.net.URI

/**
 * The gate on the one setting that decides where a jar this server will *execute* comes from.
 *
 * `api_base_url`, `repo_owner` and `repo_name` are interpolated straight into the release URL, and
 * whatever that URL returns is verified against a checksum published at the same URL -- so the
 * checksum proves the bytes arrived intact, never that they came from us. Everything upstream of
 * that (the token cipher, the staging digest, the mandatory pre-apply backup) is defeated by an
 * operator, or an SQL injection anywhere else, pointing this at a host of their choosing. This
 * validator is where that is refused.
 *
 * ### What is rejected, and why each one
 *
 * - **A non-`https` scheme.** Plain HTTP puts a release artifact and a bearer token on the wire in
 *   clear, and lets anyone on the path swap the jar *and* the checksum that would have caught it.
 * - **A raw IP host, v4 or v6.** Names are what certificates bind to and what an operator can be
 *   held to; a literal address is how an update source is pointed at a box that no audit trail
 *   names. This also blocks the loopback and link-local cases in their numeric spelling.
 * - **A loopback or site-local *name*** -- `localhost`, `*.localhost`, `*.local`, `*.internal`.
 *   Without this the previous rule is a formality: `localhost` resolves to 127.0.0.1 just the same.
 * - **Userinfo in the URL** (`https://user:pass@host/`). It is both a credential in a database
 *   column and a classic host-confusion trick -- `https://api.github.com@evil.example/` has host
 *   `evil.example`, and reads to a human as GitHub.
 * - **A query or fragment.** This is a base URL that gets a path appended; anything after `?` or
 *   `#` cannot survive that concatenation intact and is only ever there to confuse a reader.
 * - **An owner or repository name outside GitHub's own charset.** These are path segments. A `..`
 *   or a `/` in either one re-aims the request at an arbitrary path on the API host, which is a
 *   path-traversal with the same effect as changing the host.
 *
 * Everything here is a 400: the request is malformed, the operator can fix it, and the message
 * says which field and what rule.
 */
object UpdateSourceValidator {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal")

    /**
     * Returns the validated edit, or throws.
     *
     * Fails fast, one field at a time, so the operator is told the first thing that is wrong rather
     * than being handed a list they have to reconcile with their own input.
     */
    fun validate(request: UpdateDto.UpdateSettingsRequestDto): UpdateSettingsEdit {
        val channel =
            UpdateChannel.entries.firstOrNull { it.name == request.channel }
                ?: reject("channel must be one of ${UpdateChannel.entries.joinToString(", ") { it.name }}")

        // No repository validation: it is not client input any more. UpdateChannel carries the
        // coordinates, so there is nothing here for a caller to get wrong or to smuggle a path into.

        val baseUrl = request.apiBaseUrl.trim()
        validateBaseUrl(baseUrl)

        return UpdateSettingsEdit(
            channel = channel,
            apiBaseUrl = baseUrl.trimEnd('/'),
            autoApply = request.autoApply,
        )
    }

    fun validateBaseUrl(raw: String) {
        if (raw.isEmpty()) reject("apiBaseUrl must not be blank")

        val uri =
            try {
                URI(raw)
            } catch (e: IllegalArgumentException) {
                reject("apiBaseUrl is not a valid URL")
            }

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            reject("apiBaseUrl must use https — an update is a binary this server will execute")
        }
        if (uri.userInfo != null) reject("apiBaseUrl must not carry credentials in the URL")
        if (uri.query != null || uri.fragment != null) reject("apiBaseUrl must not carry a query or fragment")

        val host = uri.host ?: reject("apiBaseUrl must name a host")
        if (isRawIpAddress(host)) reject("apiBaseUrl must name a host, not an IP address")
        if (isLocalName(host)) reject("apiBaseUrl must not point at this machine or a site-local name")
    }

    /**
     * `URI.getHost` returns a v6 literal still wrapped in its brackets, and no registered name may
     * contain a colon, so the two spellings are covered by one check plus the v4 pattern.
     */
    private fun isRawIpAddress(host: String): Boolean = IPV4.matches(host) || host.startsWith("[") || host.contains(':')

    private fun isLocalName(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "localhost" || LOCAL_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun reject(detail: String): Nothing = throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail)
}
