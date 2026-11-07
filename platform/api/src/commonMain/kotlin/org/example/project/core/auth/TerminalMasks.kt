package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability

/**
 * The station axis: which APIs a station *type* exists to serve, intersected with what the identity
 * axis already allows.
 *
 * A mask can only ever **narrow**. The effective set is `identity ∩ mask`, so a mask naming a
 * capability the principal does not hold grants nothing, and a mask cannot rescue a principal the
 * identity axis refused.
 *
 * ### Where this applies, and where it deliberately does not
 *
 * **To requests authenticated by a device token, and to a JWT carrying a `station` claim.** That
 * claim is minted only by the station-session exchange, from a device token that matched a terminal
 * row, so it is a server-side fact about a station acting as itself. A person's JWT (login /
 * refresh) carries no such claim and passes as [StationClass.UNBOUND], whose mask is full. That
 * keeps the till's step-up flow governed by the identity axis: a manager lending
 * `ORDER_ITEM_CANCEL` at a station is unioned in AFTER the mask.
 *
 * [StationClass.UNBOUND] is still modelled, and still full, so the call site can apply
 * `held ∩ maskFor(...)` uniformly instead of branching. See [StationClass.UNBOUND] for why an
 * unproven station must never be narrowed.
 *
 * ### Scope
 *
 * This narrows the device-token path and the station's own JWT alike: every JWT-only route a station
 * calls through that token — tables, sessions, products, reservations, notifications, printing — is
 * narrowed here. The kitchen, display and kiosk masks are still groundwork for the shells Phase D
 * adds.
 *
 * ### The launch gate
 *
 * **Every mask must contain [Capability.TERMINAL_READ].** `GET /api/v1/terminals/me-by-device` is
 * how a login-less station discovers its own role at startup, before anyone has logged in, and it
 * requires `TERMINAL_READ`. A mask omitting it bricks that station at launch. The parent spec's
 * sketch had `KITCHEN to setOf(ORDER_READ)`, which would have done exactly that; `TerminalMaskMatrixTest`
 * asserts the rule rather than trusting each mask to remember it.
 */
object TerminalMasks {
    /** What the till itself needs to boot: identify its terminal, read config, sync. */
    private val STATION_BASELINE: Set<Capability> =
        setOf(
            Capability.CHANGELOG_READ,
            Capability.CONFIG_READ,
            Capability.NOTIFICATION_READ,
            Capability.SHOP_INFO_READ,
            Capability.SYNC_READ,
            // The launch gate. See the class KDoc — omitting this bricks the station at startup.
            Capability.TERMINAL_READ,
        )

    /**
     * Login-less order taking, and the order-station shell that Phase C adds on top.
     *
     * Deliberately close to the `ORDER_ONLY` permission group, and derived the same way — from what
     * taking an order actually touches, not from what the phrase "order station" suggests. Printing
     * is in it because an order goes to the kitchen; tables because orders are taken against a
     * session; customers because that flow attaches one; promotions because they change the line
     * price.
     *
     * No `SELF_WRITE`: a device has no PIN of its own to change. No `BILL_*`: billing does not
     * happen at an order station at all — the guest is settled at the till, under the name of the
     * employee signed in there. There is no step-up path into billing here.
     */
    private val ORDER_STATION_MASK: Set<Capability> =
        STATION_BASELINE +
            setOf(
                Capability.CATALOG_READ,
                Capability.CUSTOMER_READ,
                Capability.CUSTOMER_WRITE,
                Capability.ORDER_READ,
                Capability.ORDER_WRITE,
                Capability.PRINTER_READ,
                Capability.PRINTER_WRITE,
                Capability.PROMOTION_READ,
                // The station shell shows reservations: list, take, amend. Not delete — that
                // stays with the till, see the order-station shell design (2026-09-06).
                Capability.RESERVATION_READ,
                Capability.RESERVATION_WRITE,
                Capability.SELF_READ,
                Capability.TABLE_READ,
                Capability.TABLE_WRITE,
                Capability.TERMINAL_SELF_REGISTER,
            )

    /**
     * A kitchen display reads the order queue and prints. It sells nothing.
     *
     * Reserved — the shell is a later phase — so this is deliberately tight. If Phase D finds it too
     * tight the failure is a visible 403 and a considered widening, which is the right direction for
     * a mask to be wrong in.
     */
    private val KITCHEN_MASK: Set<Capability> =
        STATION_BASELINE +
            setOf(
                Capability.CATALOG_READ,
                Capability.ORDER_READ,
                Capability.PRINTER_READ,
                Capability.PRINTER_WRITE,
            )

    /** A customer-facing display shows the order being built and the menu. Read-only. */
    private val CUSTOMER_DISPLAY_MASK: Set<Capability> =
        STATION_BASELINE +
            setOf(
                Capability.CATALOG_READ,
                Capability.ORDER_READ,
            )

    /** Self-service ordering: browse the menu, place an order, print a ticket. No table service. */
    private val KIOSK_MASK: Set<Capability> =
        STATION_BASELINE +
            setOf(
                Capability.CATALOG_READ,
                Capability.ORDER_READ,
                Capability.ORDER_WRITE,
                Capability.PRINTER_READ,
                Capability.PRINTER_WRITE,
                Capability.PROMOTION_READ,
            )

    private val MASKS: Map<StationClass, Set<Capability>> =
        mapOf(
            // A till is operated by a person over a JWT, so the identity axis is the gate and a mask
            // here would only duplicate it. Full, not narrowed.
            StationClass.CASHIER to Capability.entries.toSet(),
            StationClass.ORDER_STATION to ORDER_STATION_MASK,
            StationClass.KITCHEN to KITCHEN_MASK,
            StationClass.CUSTOMER_DISPLAY to CUSTOMER_DISPLAY_MASK,
            StationClass.KIOSK to KIOSK_MASK,
            // Untrusted, therefore never narrowed. See StationClass.UNBOUND.
            StationClass.UNBOUND to Capability.entries.toSet(),
        )

    /** The mask for [station]. Total: every [StationClass] has one, so there is no default to get wrong. */
    fun forStation(station: StationClass): Set<Capability> = MASKS[station] ?: error("no mask for $station — MASKS must cover every StationClass")

    /**
     * The effective capability set: what the principal holds, narrowed to what this station type is for.
     *
     * The only way a mask should ever be applied. Writing the intersection by hand at a call site is
     * how one of them ends up doing it in the wrong order, or not at all.
     */
    fun apply(
        held: Set<Capability>,
        station: StationClass,
    ): Set<Capability> = held intersect forStation(station)
}
