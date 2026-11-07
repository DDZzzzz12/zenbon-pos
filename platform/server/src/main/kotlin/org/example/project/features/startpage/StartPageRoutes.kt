package org.example.project.features.startpage

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.example.project.BuildConfig
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.service.InitialSetupService
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StartPageRoutes")

private const val COOKIE = "pos_start"
private const val ONE_YEAR_SECONDS = 365 * 24 * 60 * 60
private const val CSP =
    "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'"
private val HTML_UTF8 = ContentType.Text.Html.withCharset(Charsets.UTF_8)

/**
 * `/` and `/start-page`. Outside `/api`, so RouteAuthorizationPlugin does not check them
 * (RouteAuthorizationPlugin skips every non-`/api` path): public by construction, which is intended.
 */
fun Route.startPageRoutes() {
    val configManager by inject<IShopConfigurationManager>()
    val setupService by inject<InitialSetupService>()
    // Per application: the remembered "initialised" never outlives the application that observed it.
    val probe = SetupStateProbe(setupService)

    suspend fun ApplicationCall.answerStartPage(withBody: Boolean) {
        val available = WebEntryRegistry.available(configManager)
        val serverDefault = configManager.getStartPageDefault()
        val cookie = request.cookies[COOKIE]
        val decision =
            StartPageResolver.resolve(
                StartPageInput(
                    initialized = probe.isInitialized(),
                    forceStartPage = request.queryParameters.contains("start"),
                    cookieValue = cookie,
                    serverDefault = serverDefault,
                    available = available.mapNotNull { it.target }.toSet(),
                ),
            )
        applySecurityHeaders()
        if (decision.clearCookie) clearStartCookie()
        when (decision) {
            is StartPageDecision.Redirect -> redirect(decision.path, HttpStatusCode.Found)
            is StartPageDecision.Show -> {
                if (!withBody) {
                    respond(htmlHeadResponse())
                    return
                }
                val model =
                    StartPageRenderer.Model(
                        shopName = firstShopName(),
                        version = BuildConfig.APP_VERSION,
                        entries = available,
                        browserDefault = StartPageTarget.fromWire(cookie)?.takeUnless { decision.clearCookie },
                        serverDefault = serverDefault,
                        defaultUnavailable = decision.defaultUnavailable,
                        language = StartPageLanguage.fromAcceptLanguage(request.headers[HttpHeaders.AcceptLanguage]),
                    )
                respondText(StartPageRenderer.render(model), HTML_UTF8)
            }
        }
    }

    /**
     * Start page.
     *
     * Description: Public and unauthenticated. Forwards an uninitialised server to /admin/ for setup; otherwise forwards to this browser's default page (cookie `pos_start`) or the server-wide default, or shows a small localized page linking the web pages this server serves (Web-Admin, browser stations when enabled, manual, API documentation when enabled). `?start` always shows the page.
     *
     * Tag: infrastructure
     * Response: 302 Forwarded to the setup, the browser's default or the server's default page.
     */
    get("/") { call.answerStartPage(withBody = true) }

    /**
     * Start page headers.
     *
     * Description: The same answer as `GET /` — status, forward and headers — without a body, for monitoring and link checkers.
     *
     * Tag: infrastructure
     * Response: 302 Forwarded to the setup, the browser's default or the server's default page.
     */
    head("/") { call.answerStartPage(withBody = false) }

    /**
     * Set or reset this browser's default page.
     *
     * Description: Public form post from the start page. `target` is `admin`, `pos` or `none`; anything else is a 400. Stores the choice in the cookie `pos_start` (one year, HttpOnly, SameSite=Lax) and answers 303 to the chosen page (or to `/?start` for `none`). Not a security setting: it only decides where `/` forwards, every target still requires its own login and pairing, and a forged value can only name an allowlisted page — so no CSRF token.
     *
     * Tag: infrastructure
     * Response: 303 Forwarded to the chosen page, or back to the start page for `none`.
     * Response: 400 Unknown target.
     */
    post("/start-page") {
        val target = StartPageTarget.fromWire(call.receiveParameters()["target"])
        call.applySecurityHeaders()
        val path = target?.path
        when {
            target == null -> call.respond(HttpStatusCode.BadRequest)
            path == null -> {
                call.clearStartCookie()
                call.redirect("/?start", HttpStatusCode.SeeOther)
            }
            else -> {
                call.response.cookies.append(startCookie(target.wire, ONE_YEAR_SECONDS))
                call.redirect(path, HttpStatusCode.SeeOther)
            }
        }
    }
}

/**
 * The page title, read from the first shop — the same "first shop" the config manager uses. A direct read
 * rather than ShopInfoService.getAllShopInfo(), which logs at INFO on every call and would let anonymous
 * visitors to `/` flood the log. A missing or unreadable record falls back to the generic title.
 */
private suspend fun firstShopName(): String? =
    try {
        dbQuery {
            ShopInfo
                .all()
                .limit(1)
                .firstOrNull()
                ?.shopName
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Start page: shop name unavailable", e)
        null
    }

private suspend fun ApplicationCall.redirect(
    path: String,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.Location, path)
    respond(status)
}

private fun ApplicationCall.applySecurityHeaders() {
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("Content-Security-Policy", CSP)
    response.header("X-Content-Type-Options", "nosniff")
}

private fun ApplicationCall.clearStartCookie() {
    response.cookies.append(startCookie("", 0))
}

private fun startCookie(
    value: String,
    maxAge: Int,
): Cookie =
    Cookie(
        name = COOKIE,
        value = value,
        maxAge = maxAge,
        path = "/",
        httpOnly = true,
        extensions = mapOf("SameSite" to "Lax"),
    )

/** `HEAD /` on the page: GET's status and content type, no body. */
private fun htmlHeadResponse(): OutgoingContent =
    object : OutgoingContent.NoContent() {
        override val status: HttpStatusCode = HttpStatusCode.OK
        override val contentType: ContentType = HTML_UTF8
    }
