package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isTerminal

/**
 * Who may set a custom price, and who may discount steeply.
 *
 * **In `shared/`, not `pos-server/`, deliberately.** CLAUDE.md rule 8 requires the guard at both
 * levels — the till must grey out the discount field the server would reject — and defect D2 is what
 * happens when the two sides each own a copy of the rule. Client track C1–C3 gates on these same
 * functions rather than re-deriving the thresholds.
 *
 * Extracted from `OrderServiceImpl` in Stage 3, where the rule had been an inline
 * `role.isManagerOrHigher()` flag shared by both checks and a bare `0.80` literal. Splitting it out
 * gives the threshold a name and makes the two authorities separately testable, which is the point
 * of having two capabilities instead of one flag.
 *
 * Pure by design: no database, no principal lookup. The caller resolves the capability set once and
 * passes it in.
 */
object PriceAuthority {
    /**
     * Discount multipliers at or below this need [Capability.DISCOUNT_GRANT].
     *
     * A *multiplier*, not a percentage: `0.80` is a 20 % discount. The comparison is strictly less
     * than, so exactly 20 % off is unprivileged — preserved from the pre-Stage-3 behaviour rather
     * than tidied, because tightening it would silently start rejecting orders in the field.
     */
    const val STEEP_DISCOUNT_THRESHOLD: Double = 0.80

    /**
     * The capability that lets a line be booked at a custom price.
     *
     * Named here so the till's gate and the server's check read one value: the till greys out the
     * price field on `holds(CUSTOM_PRICE_AUTHORITY)`, the server calls [mayUseCustomPrice].
     */
    val CUSTOM_PRICE_AUTHORITY: Capability = Capability.PRICE_OVERRIDE

    /**
     * The capability that lifts the 20 % discount cap.
     *
     * **Not** [CUSTOM_PRICE_AUTHORITY]. The till once unlocked steep discounts on `PRICE_OVERRIDE`
     * while the server checked `DISCOUNT_GRANT` (finding T5-5), so a group holding only the former
     * was offered a 50 % discount the server then refused with 403. The till now gates on this
     * value, the server on [mayApplyDiscount], and both read it from here.
     */
    val STEEP_DISCOUNT_AUTHORITY: Capability = Capability.DISCOUNT_GRANT

    /** The multiplier meaning "no discount", used when a line omits one. */
    const val NO_DISCOUNT: Double = 1.0

    // Bounds live in OrderItemDto.validate() — `discount.isFinite()` and `discount in 0.0..1.0` —
    // and are deliberately NOT repeated here. They are a different concern (no capability makes a
    // negative multiplier legitimate), they already have tests, and a second copy of a range check
    // is how the two drift apart.

    /** True when [discount] is steeper than the unprivileged limit and therefore needs authority. */
    fun isSteepDiscount(discount: Double?): Boolean = (discount ?: NO_DISCOUNT) < STEEP_DISCOUNT_THRESHOLD

    /**
     * May this principal book a line at a price the product does not itself allow?
     *
     * `productAllowsCustomPrice` is the per-product escape hatch (an open-price item, a weighed
     * good): where the product allows it, no capability is needed by anyone.
     */
    fun mayUseCustomPrice(
        held: Set<Capability>,
        productAllowsCustomPrice: Boolean,
    ): Boolean = productAllowsCustomPrice || CUSTOM_PRICE_AUTHORITY in held

    /** May this principal apply [discount]? Shallow discounts need no authority at all. */
    fun mayApplyDiscount(
        held: Set<Capability>,
        discount: Double?,
    ): Boolean = !isSteepDiscount(discount) || STEEP_DISCOUNT_AUTHORITY in held

    /**
     * May a line be discounted or custom-priced at all in an order registered under an employee of
     * [attributedRole]? False for the shared station account ([EmployeeRole.isTerminal] — what a
     * station JWT and a bare station device token act as), true for a person. Checked before, and
     * independently of, the capability checks: no capability and no step-up makes it true.
     *
     * **Why this is not a capability.** The two capabilities above only gate the *steep* discount and
     * the custom price; a discount of up to 20 % needs no capability at all, so the order-station
     * mask — which already withholds `DISCOUNT_GRANT` and `PRICE_OVERRIDE` — could not stop one
     * (finding E-2: a station booked 16,90 € at 15,21 €). Expressing "may discount at all" as a new
     * capability would need every till group re-seeded by a migration, and a capability can be LENT
     * by a step-up.
     *
     * **By attribution, not by device** (owner's decision, 2026-09-28): pricing belongs with a person
     * who answers for it, so what decides is WHO the order is registered under. A manager's own login
     * on the order-station device prices like that manager anywhere else (the order records the
     * manager as its employee and the station's terminal as the one it is signed under). A step-up at
     * the station lends capabilities to the station account; it does not change attribution, so the
     * order is still the station account's and is still refused.
     *
     * The server refuses such a line with 403 (`OrderServiceImpl.createOrder`); the till hides the
     * discount and custom-price controls on the same answer, so the two sides cannot disagree.
     */
    fun mayPriceLinesAs(attributedRole: EmployeeRole): Boolean = !attributedRole.isTerminal()

    /** True when [discount] takes anything off the line — any multiplier below [NO_DISCOUNT]. */
    fun isDiscounted(discount: Double?): Boolean = (discount ?: NO_DISCOUNT) < NO_DISCOUNT
}
