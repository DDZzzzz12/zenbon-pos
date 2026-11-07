package org.example.project.features.update.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.example.project.features.update.domain.service.ClientUpdateService
import org.koin.ktor.ext.inject

/**
 * The Android build tills download -- reachable **without a session**, because the check runs on
 * the login screen. `UpdateRules` marks both routes `Public`; the reasoning is recorded there.
 *
 * Deliberately not inside [updateRoutes]: that family sits behind one `authenticate("auth-jwt")`,
 * and a route inside it would demand a token.
 */
fun Route.clientUpdateRoutes() {
    val service by inject<ClientUpdateService>()

    route("/api/update/client/android") {
        /**
         * Describe the staged Android build.
         *
         * Description: Public. The versionCode a till compares with its own, plus version name, SHA-256, size and release notes.
         *
         * Tag: update
         * Response: 404 No Android build is staged on this server.
         */
        get {
            call.respond(HttpStatusCode.OK, service.androidClient())
        }

        /**
         * Download the staged Android build.
         *
         * Description: Public. Streams the APK that was verified against its published SHA-256 before it was stored; the digest is also sent as X-SHA256.
         *
         * Tag: update
         * Response: 404 No Android build is staged on this server.
         */
        get("/download") {
            val download = service.androidClientDownload()
            call.response.header("X-SHA256", download.sha256)
            call.respondFile(download.file)
        }
    }
}
