package org.example.project.features.update.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.update.domain.service.UpdateService
import org.koin.ktor.ext.inject

/**
 * The software-update endpoints — reads behind `UPDATE_READ`, everything else behind
 * `UPDATE_MANAGE` (see `UpdateRules`).
 *
 * The token is write-only across this whole family: it can be set and cleared, and no route here
 * can return it. `UpdateDto.SettingsDto` has no token property to populate, so that is a property
 * of the types rather than a rule a handler has to remember.
 */
fun Route.updateRoutes() {
    val service by inject<UpdateService>()

    authenticate("auth-jwt") {
        route("/api/update") {
            /**
             * Read the update status.
             *
             * Description: Requires UPDATE_READ. The running version, what the last check found, and whether a verified artifact is staged and ready to install. Touches no network.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_READ.
             */
            get("/status") {
                call.respond(HttpStatusCode.OK, service.status())
            }

            /**
             * Read the update source settings.
             *
             * Description: Requires UPDATE_READ. Channel, repository and API base URL, plus whether a GitHub token is stored. The token itself is never returned — only a masked preview of its last four characters and the time it was stored.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_READ.
             */
            get("/settings") {
                call.respond(HttpStatusCode.OK, service.settings())
            }

            /**
             * Replace the update source settings.
             *
             * Description: Requires UPDATE_MANAGE. The API base URL decides which host this server fetches and then executes a jar from, so it must be an https URL naming a host — not an IP address, not a loopback or site-local name, with no embedded credentials, query or fragment — and the owner and repository must be valid GitHub names.
             *
             * Tag: update
             * Response: 400 The body named an update source this server refuses to fetch from; the detail says which rule.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             */
            put("/settings") {
                val body = call.receive<UpdateDto.UpdateSettingsRequestDto>()
                call.respond(HttpStatusCode.OK, service.saveSettings(body))
            }

            /**
             * Store the GitHub token.
             *
             * Description: Requires UPDATE_MANAGE. The token is sealed with this host's UPDATE_TOKEN_KEY before it is written and can never be read back through the API.
             *
             * Tag: update
             * Response: 400 The token was blank.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             * Response: 409 No UPDATE_TOKEN_KEY is provisioned on this host, so the token could never be read back.
             */
            put("/token") {
                val body = call.receive<UpdateDto.SetTokenRequestDto>()
                call.respond(HttpStatusCode.OK, service.setToken(body.token))
            }

            /**
             * Remove the stored GitHub token.
             *
             * Description: Requires UPDATE_MANAGE. Idempotent. The private channel stops working until a new token is stored.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             */
            delete("/token") {
                call.respond(HttpStatusCode.OK, service.clearToken())
            }

            /**
             * Check GitHub for a newer release.
             *
             * Description: Requires UPDATE_MANAGE. Asks GitHub and records the outcome; downloads nothing. Never fails for an expected condition — a rejected token or an unreachable GitHub comes back as a recorded outcome in the summary.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             */
            post("/check") {
                call.respond(HttpStatusCode.OK, service.checkNow())
            }

            /**
             * Download and verify the newer release.
             *
             * Description: Requires UPDATE_MANAGE. Downloads the release archive and verifies it against the SHA256SUMS published with it before putting it on disk. Never installs anything.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             * Response: 502 The artifact could not be fetched, or did not match its published checksum.
             */
            post("/stage") {
                call.respond(HttpStatusCode.OK, service.stageNow())
            }

            /**
             * Install the staged release.
             *
             * Description: Requires UPDATE_MANAGE. Runs the same preflight the nightly job runs, takes a database backup, swaps the jar and exits; systemd brings the new binary up. The connection drops mid-flight — poll /api/update/status after the restart.
             *
             * Tag: update
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks UPDATE_MANAGE.
             * Response: 409 The preflight is blocking — the detail names the blocker — or nothing is staged.
             * Response: 500 The staged archive carried no usable server jar, or the pre-apply backup was not written.
             */
            post("/apply") {
                call.respond(HttpStatusCode.OK, service.applyNow())
            }
        }
    }
}
