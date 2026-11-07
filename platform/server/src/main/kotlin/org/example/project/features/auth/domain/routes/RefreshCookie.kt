@file:OptIn(ExperimentalTime::class)

package org.example.project.features.auth.domain.routes

import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.renderSetCookieHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import org.example.project.auth.data.LoginResponse
import org.example.project.auth.data.RefreshTokenRequest
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.JwtConfig
import org.example.project.core.utils.AuthTransport
import org.example.project.infrastructure.errors.PosException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The refresh-token cookie. The `__Host-` prefix makes a browser refuse it unless it is Secure,
 * `Path=/` and carries no `Domain` — so no other host on the network can plant or overwrite it.
 */
internal const val REFRESH_COOKIE = "__Host-pos_refresh"

internal fun ApplicationCall.wantsCookieTransport(): Boolean = request.headers[AuthTransport.HEADER].equals(AuthTransport.COOKIE, ignoreCase = true)

/**
 * The refresh token this call presents.
 *
 * Cookie transport reads ONLY the cookie, and only because [AuthTransport.HEADER] was sent — a
 * header no cross-site form can set and no cross-origin fetch can send without a CORS preflight this
 * server never approves. That is the CSRF defence; `SameSite=Strict` is the second layer. Body
 * transport reads ONLY the body and ignores any cookie.
 */
internal suspend fun ApplicationCall.presentedRefreshToken(): String {
    if (wantsCookieTransport()) {
        return request.cookies[REFRESH_COOKIE, CookieEncoding.RAW]?.takeIf { it.isNotBlank() }
            ?: throw PosException(PosErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatusCode.Unauthorized, "No refresh cookie")
    }
    return receive<RefreshTokenRequest>().refreshToken?.takeIf { it.isNotBlank() }
        ?: throw PosException(PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, "Refresh token is required")
}

/** Responds to a login or refresh; under cookie transport the refresh token moves into the cookie. */
internal suspend fun ApplicationCall.respondIssuedSession(response: LoginResponse) {
    val refresh = response.token.refreshToken
    if (!wantsCookieTransport() || refresh == null) {
        respond(HttpStatusCode.OK, response)
        return
    }
    val expiresAt =
        checkNotNull(JwtConfig.validateToken(refresh)?.expiresAt) { "A just-issued refresh token did not verify" }
    val maxAge = (expiresAt - Clock.System.now()).inWholeSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    setRefreshCookie(value = refresh, maxAgeSeconds = maxAge)
    respond(HttpStatusCode.OK, response.copy(token = response.token.copy(refreshToken = null)))
}

internal fun ApplicationCall.expireRefreshCookie() = setRefreshCookie(value = "", maxAgeSeconds = 0)

/**
 * Runs [block]; under cookie transport a 401 also expires the refresh cookie before the error
 * propagates. A browser cannot delete an HttpOnly cookie itself, so without this a rejected cookie
 * (revoked, rotated-out, expired) would be replayed on every page load — and replaying a rotated-out
 * refresh token outside the short same-device grace window revokes every session the employee holds.
 * Only 401: a 5xx (database down) must not log the admin out.
 */
internal suspend fun ApplicationCall.expireRefreshCookieOnRejection(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: PosException) {
        if (wantsCookieTransport() && e.httpStatus == HttpStatusCode.Unauthorized) expireRefreshCookie()
        throw e
    }
}

/**
 * Written as a raw `Set-Cookie` header on purpose: it lets the header be built with
 * `includeEncoding = false`, which omits Ktor's internal `$x-enc` extension attribute — an
 * implementation detail of Ktor's own cookie round-trip encoding, not something a browser or another
 * server should ever see on the wire. `Secure` is set unconditionally because TLS terminates at the
 * Caddy proxy in production; Chromium and Firefox also accept `Secure` cookies on `http://localhost`
 * for development (WebKit/Safari may not), so this never blocks local testing there.
 */
private fun ApplicationCall.setRefreshCookie(
    value: String,
    maxAgeSeconds: Int,
) {
    val header =
        renderSetCookieHeader(
            name = REFRESH_COOKIE,
            value = value,
            encoding = CookieEncoding.RAW,
            maxAge = maxAgeSeconds,
            path = "/",
            secure = true,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
            includeEncoding = false,
        )
    response.headers.append(HttpHeaders.SetCookie, header)
}
