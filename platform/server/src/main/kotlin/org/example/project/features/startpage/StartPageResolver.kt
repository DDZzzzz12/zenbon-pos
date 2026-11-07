package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget

/**
 * The resolution order for `GET /` (spec §3.4): setup → `?start` → browser cookie → server default → page.
 * Pure, so every branch is unit-tested. A redirect only ever goes to a [StartPageTarget.path] — never to a
 * value taken from the request.
 */
object StartPageResolver {
    private const val SETUP_PATH = "/admin/"

    fun resolve(input: StartPageInput): StartPageDecision {
        if (!input.initialized) return StartPageDecision.Redirect(SETUP_PATH)
        val cookieTarget = StartPageTarget.fromWire(input.cookieValue)
        // A cookie that is not a real target (forged, garbage, or "none") must not survive.
        val clearCookie = input.cookieValue != null && (cookieTarget == null || cookieTarget == StartPageTarget.NONE)
        if (input.forceStartPage) return StartPageDecision.Show(defaultUnavailable = false, clearCookie = clearCookie)

        val browserChoice = cookieTarget?.takeIf { it != StartPageTarget.NONE }
        if (browserChoice != null) return forward(browserChoice, input.available, clearCookie)

        val serverChoice = input.serverDefault.takeIf { it != StartPageTarget.NONE }
        if (serverChoice != null) return forward(serverChoice, input.available, clearCookie)

        return StartPageDecision.Show(defaultUnavailable = false, clearCookie = clearCookie)
    }

    private fun forward(
        target: StartPageTarget,
        available: Set<StartPageTarget>,
        clearCookie: Boolean,
    ): StartPageDecision {
        val path = target.path
        return if (path != null && target in available) {
            StartPageDecision.Redirect(path, clearCookie)
        } else {
            StartPageDecision.Show(defaultUnavailable = true, clearCookie = clearCookie)
        }
    }
}
