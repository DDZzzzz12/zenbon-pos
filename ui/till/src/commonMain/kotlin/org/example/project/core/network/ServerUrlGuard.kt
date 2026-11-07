package org.example.project.core.network

import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.core.utils.ServerUrlVerdict

/**
 * Refuses — before anything leaves the device — every request whose URL [ServerUrlPolicy] does not
 * accept: https anywhere, plain http only to loopback. The refusal is an
 * [InsecureServerUrlException], i.e. a transport failure.
 *
 * The launch gate alone is not enough. The till's client stamps `X-Device-Token` (and, once signed
 * in, the bearer) on every request, and several callers run whatever the gate shows: the login
 * screen's 5-second health/setup probe, the notification stream, the Auth plugin's refresh. A shop
 * that auto-updated the server jar without re-running the installer still serves plain HTTP on
 * :8080, and without this guard the till kept sending its long-lived device token there in clear
 * text while the recovery screen was up.
 *
 * Hooked on [SendingRequest] (the send pipeline's State phase, just before the engine) rather than
 * `onRequest`: the send pipeline runs once per wire attempt, so a redirect followed by HttpRedirect
 * and the retry the Auth plugin makes after a refresh are checked too, and the URL is the final one
 * after DefaultRequest merged it.
 *
 * Installed by composeApp only, via [installTillClientPlugins] (called from `NetworkModule`).
 * adminApp is served same-origin by pos-server and must keep working over http on shops that have
 * not re-run the installer, so the shared `createPosHttpClient` does not install it.
 */
val ServerUrlGuard =
    createClientPlugin("ServerUrlGuard") {
        on(SendingRequest) { request, _ ->
            val verdict = ServerUrlPolicy.evaluate(request.url.buildString())
            if (verdict != ServerUrlVerdict.ACCEPTED) throw InsecureServerUrlException(verdict)
        }
    }
