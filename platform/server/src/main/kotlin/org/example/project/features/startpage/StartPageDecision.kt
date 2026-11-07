package org.example.project.features.startpage

/** What `GET /` does: forward, or render the page (optionally clearing a forged cookie, with notes). */
sealed interface StartPageDecision {
    val clearCookie: Boolean

    data class Redirect(
        val path: String,
        override val clearCookie: Boolean = false,
    ) : StartPageDecision

    data class Show(
        val defaultUnavailable: Boolean,
        override val clearCookie: Boolean = false,
    ) : StartPageDecision
}
