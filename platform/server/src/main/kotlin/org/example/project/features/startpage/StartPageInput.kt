package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget

/** Everything the resolver needs, as plain values — no Ktor, no database. */
data class StartPageInput(
    val initialized: Boolean,
    val forceStartPage: Boolean,
    val cookieValue: String?,
    val serverDefault: StartPageTarget,
    val available: Set<StartPageTarget>,
)
