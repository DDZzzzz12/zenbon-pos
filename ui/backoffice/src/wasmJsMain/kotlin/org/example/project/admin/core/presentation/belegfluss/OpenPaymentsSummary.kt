package org.example.project.admin.core.presentation.belegfluss

/**
 * What the *Zuordnen* tile of the Belegfluss strip states on every screen: the payments still waiting to be
 * assigned — the Zuordnung queue's server [total] for *Nicht zugeordnet* — and, when the whole queue was
 * loaded, the [sum] of their open amounts. A null [sum] means "not knowable" (partial page), never zero.
 */
data class OpenPaymentsSummary(
    val total: Long,
    val sum: String?,
    val currency: String?,
)
