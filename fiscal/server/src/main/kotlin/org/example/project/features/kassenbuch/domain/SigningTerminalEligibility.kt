package org.example.project.features.kassenbuch.domain

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.SigningDefect
import org.example.project.features.terminal.domain.models.Terminal

/**
 * Which terminals may be designated as the Ladenkasse's signing till. One definition behind both the
 * candidate list and the designation PUT, so the dropdown never offers a till the save refuses.
 *
 * CASHIER only: the Ladenkasse drawer sits at a till. An order station is fiscal (it signs
 * Bestellungen) but holds no drawer.
 */
object SigningTerminalEligibility {
    /** Null when [terminal] may be designated for [shop]; otherwise a short reason for the 409 detail. Call inside a transaction. */
    fun reasonIneligible(
        terminal: Terminal,
        shop: ShopInfo,
        mode: TseMode,
    ): String? {
        val terminalShopId = terminal.shopInfo?.id?.value ?: shop.id.value
        if (terminalShopId != shop.id.value) return "belongs to another shop"
        if (terminal.role != TerminalRole.CASHIER.name) return "not a CASHIER till"
        return SigningDefect.of(terminal, mode)?.name
    }

    fun isEligible(
        terminal: Terminal,
        shop: ShopInfo,
        mode: TseMode,
    ): Boolean = reasonIneligible(terminal, shop, mode) == null
}
