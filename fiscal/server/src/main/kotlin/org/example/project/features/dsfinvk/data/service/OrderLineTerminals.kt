package org.example.project.features.dsfinvk.data.service

import org.example.project.export.dsfinvk.builder.BonIdNamespace
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders

/**
 * One export's memo of the terminal each order's items were entered on, so every line of the same
 * order — order, bill, storno, cancellation — names the same till ([DsfinvkTerminalAttribution.entryTerminalOf]).
 *
 * Seeded from the orders the export already loaded together with their TSE transactions, so a
 * closing's ~1000 lines cost no query per line. An order outside that set (an item billed in this
 * window but ordered earlier) is resolved once on first use and then memoised.
 */
class OrderLineTerminals(
    seed: Collection<Orders> = emptyList(),
) {
    private val codeByOrderId = HashMap<Long, String?>()

    init {
        seed.forEach { codeByOrderId[it.id.value] = resolve(it) }
    }

    /**
     * The candidate POS_TERMINAL_ID of a line recording [item], resolved in this order: the order's
     * signing client, the order's recorded terminal, and — only for an item that has no order at
     * all — [orderlessFallback], the attributed TERMINAL_ID of the line's own Bon (the bill or
     * cancellation that carries it). An orderless item was entered at the till that settled it, so
     * its own Bon's signature names that till; an item WITH an order never falls back to the Bon,
     * even when the order is unattributable.
     */
    fun of(
        item: OrderItem,
        orderlessFallback: String? = null,
    ): String? {
        // item.order is served from the transaction's entity cache for every order the export
        // already loaded, so this costs no query for the seeded orders.
        val order = item.order ?: return orderlessFallback
        return codeByOrderId.getOrPut(order.id.value) { resolve(order) }
    }

    private fun resolve(order: Orders): String? =
        DsfinvkTerminalAttribution.entryTerminalOf(
            DsfinvkTerminalAttribution.Vorgang(
                bonId = BonIdNamespace.order(order.id.value),
                signingClientId = DsfinvkTerminalAttribution.signingClientOf(order.tseTransaction),
                recordedTerminalCode = order.terminal?.clientId,
            ),
        )
}
