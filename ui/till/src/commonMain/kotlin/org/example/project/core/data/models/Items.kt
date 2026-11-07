package org.example.project.core.data.models

import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class Items(
    val id: Long,
    /** Order item unique identifier */
    val orderItemIds: List<Long>? = null,
    /** Reference to parent order */
    val ordersId: Set<Long>? = null,
    /** Reference to ordered product */
    val product: Product,
    /**
     * Per-unit BASE price: the list price, or the typed custom price when [customPrice] is set.
     * It excludes the discount AND the extras/choice-option deltas — those live in [extras] and
     * [selectedOptions] and are re-added by [extrasDelta]. Nothing is ever folded in here.
     *
     * This matches the server's `OrderItemTable.unitPrice` — "Unit price at time of order",
     * likewise the bare, UI-facing figure — and `OrderServiceImpl.createOrder` sets it from
     * exactly the same two-branch rule:
     *
     * ```
     * val unitPrice = if (!itemRequest.customPrice) product.price else toBigDecimal(itemRequest.unitPrice)
     * ```
     *
     * Keeping the deltas OUT is load-bearing, not merely tidy. `toCreateItemDto` sends this field,
     * and on the `customPrice = true` branch the server uses the value it receives and adds its own
     * `extrasDelta` on top; an extras-inclusive value there is charged twice, on a `finalPrice` that
     * goes on to be TSE-signed and exported to DSFinV-K. `PosEditItemPanel` re-seeds its custom
     * price field from this same value, so the doubling would compound with every re-edit.
     *
     * The per-unit amount actually CHARGED is the server's separate `finalPrice` column (gross incl.
     * options and discount — the Anhang I p. 117 „Brutto-Einzelpreis der Bestellzeile" that TSE
     * processData signs, audit TSER-6). The client has no per-unit equivalent; [totalPrice] holds
     * the whole-line version of it, and [lineTotalAt] is what rebuilds it.
     *
     * The doc here used to read "Unit price with extras and discount applied", which described
     * `finalPrice` rather than this field. A reader trusting it could "fix" the code to match and
     * start discounting twice.
     */
    val unitPrice: Decimal,
    /**
     * Charged total for the whole line: [unitPrice] plus [extrasDelta], discounted and rounded per
     * unit, then multiplied by [quantity] — see [orderLineTotal], which is the only thing that
     * should produce this value, reached via [lineTotalAt] wherever the quantity changes.
     */
    val totalPrice: Decimal,
    /** Current status of the order item */
    val itemStatus: String? = null,
    /** Reference to applicable VAT rate */
    val vatClass: String? = null,
    /** VAT rate percentage for convenience */
    val vatRate: Double? = null,
    /** Whether custom pricing was applied */
    val customPrice: Boolean = false,
    /**
     * Discount MULTIPLIER, not a percentage off: [Decimal.ONE] is no discount, `0.90` is 10 % off.
     *
     * The server owns this representation — `OrderItemTable.discount` persists it as
     * `DECIMAL(4,2)` defaulting to `1.00` — and it round-trips through [OrderItemDto] both ways,
     * so only the type changed here. Inverting it would desynchronise the client from fiscal data
     * and `DECIMAL(4,2)` cannot even express 12.5 % off (`0.875` is scale 3).
     */
    val discount: Decimal = Decimal.ONE,
    /** Whether this is a takeaway order */
    val takeAway: Boolean = false,
    /** Whether this item was transferred from another order */
    val transferred: Boolean = false,
    /** Additional notes or customization requests */
    val sideNotes: String? = null,
    /** Quantity of items with identical properties */
    val quantity: Int = 1,
    /** List of extra IDs associated with the order item */
    val extras: List<ProductExtra>? = null,
    /** Selected grouped options (authoritative for new configured items). */
    val selectedOptions: List<SelectedOption> = emptyList(),
)

/**
 * The charged total for one order line: **discount the unit price and round it to cents first,
 * then multiply by quantity.** The order of those two steps is load-bearing — do not "simplify"
 * it into a single rounding of `unitPrice x quantity x discount`.
 *
 * This mirrors `OrderServiceImpl.createOrder` step 4e, which is authoritative because it computes
 * the number the customer is actually charged:
 *
 * ```
 * val finalPrice = ((unitPrice + extrasDelta) * discount).setScale(2, RoundingMode.HALF_UP)
 * repeat(itemRequest.quantity) { createOrderItem(..., finalPrice = finalPrice, ...) }
 * ```
 *
 * The server stores one row per unit, each carrying that same 2dp `finalPrice`, so the line the
 * receipt prints is `round((unit + extras) x discount, 2) x quantity`. It rounds per unit for a
 * fiscal reason, stated in its own comment above that line: money has to leave the price
 * computation at 2dp so the VAT split, the signed processData buckets and the printed Beleg all
 * agree to the cent (round-3 TSER-5) — scale-4 residue made the signed buckets truncate below the
 * payment sum. That is DSFinV-K-relevant; the client mirrors it rather than the reverse.
 *
 * Rounding-then-multiplying is not multiplying-then-rounding. Rounding the whole line instead
 * makes `16.90 x 3` at 15 % off read `43.10` while the receipt says `43.11`. The two agree at
 * quantity 1, which is why a quantity-1 test suite never caught it.
 *
 * [unitPrice] here is the parameter, NOT [Items.unitPrice]: it must already include the extras and
 * choice-option deltas, matching the server's `unitPrice + extrasDelta`. [Items.unitPrice] excludes
 * them, so callers holding an [Items] should go through [lineTotalAt] rather than passing the field
 * straight in — doing the latter is what silently dropped the surcharges from the cart's totals.
 *
 * @param discount the multiplier, [Decimal.ONE] meaning no discount (see [Items.discount]).
 */
fun orderLineTotal(
    unitPrice: Decimal,
    quantity: Int,
    discount: Decimal,
): Decimal =
    unitPrice
        // The server's finalPrice: one 2dp per-unit amount, stored on each of `quantity` rows.
        .times(discount, 2, MoneyRounding.HALF_UP)
        .times(Decimal.of(quantity), 2, MoneyRounding.HALF_UP)

/**
 * Per-unit surcharge from [Items.selectedOptions] — the client's mirror of the server's
 * `extrasDelta`, the second term of `OrderServiceImpl.createOrder` step 4e:
 *
 * ```
 * val finalPrice = ((unitPrice + extrasDelta) * discount).setScale(2, RoundingMode.HALF_UP)
 * ```
 *
 * **Derived, never stored.** The server does not trust a client-supplied extras total either — it
 * re-resolves every delta from the submitted `selections` against the `ChoiceGroupOptionTable`
 * placement rows (`resolveSelections`) and only then snapshots them. Deriving from the same list
 * this item sends over the wire is what keeps the preview and the charge in step; a stored field
 * would go stale the moment an option is toggled.
 *
 * Options are weighted by their own quantity, matching `resolveSelections`' `delta * sel.quantity`.
 * A selection made in `PosEditItemPanel` is always quantity 1, but the server's response snapshot
 * can carry more, and [OrderItemDto.OrderItemResponseDto.toItems] preserves it.
 *
 * Choice groups are the only way to attach an option. The legacy flat `product_extras_assignment`
 * path is gone from the order UI, and with it the divergence where the client summed both lists
 * while `createOrder` read only `selections` and silently dropped the flat extras from the charge.
 */
val Items.extrasDelta: Decimal
    get() =
        selectedOptions.fold(Decimal.ZERO) { acc, o ->
            acc + o.priceDelta * Decimal.of(o.quantity)
        }

/**
 * This line's charged total at [quantity], re-adding [extrasDelta] to the extras-exclusive
 * [Items.unitPrice] exactly as the server does before discounting.
 *
 * Every site that recomputes a total after a quantity change must go through this rather than
 * calling [orderLineTotal] on [Items.unitPrice] directly — that shortcut silently drops the extras,
 * which is what made pressing "+" on a configured line erase its surcharges from the displayed
 * total while the options stayed on the item.
 */
fun Items.lineTotalAt(quantity: Int): Decimal = orderLineTotal(unitPrice + extrasDelta, quantity, discount)

fun Items.toCreateItemDto(): OrderItemDto.CreateOrderItemRequestDto =
    OrderItemDto.CreateOrderItemRequestDto(
        productId = this.product.id,
        // unitPrice crosses the wire as an exact decimal string. `discount` is a multiplier (ratio,
        // not a money amount) and stays Double on the wire — see the DTO note.
        unitPrice = this.unitPrice.toPlainString(),
        quantity = this.quantity,
        discount = this.discount.toDoubleLossy(),
        customPrice = this.customPrice,
        takeAway = this.takeAway,
        sideNotes = this.sideNotes,
        // `extras` (the legacy flat list) is deliberately not sent. createOrder reads it ONLY when
        // `selections` is null, so sending both meant the flat extras were priced by the client,
        // silently dropped by the server, and missing from the receipt and DSFinV-K. Options now
        // reach the server one way, through grouped selections, which it re-prices authoritatively.
        selections =
            this.selectedOptions
                .takeIf { it.isNotEmpty() }
                ?.map { o ->
                    OrderItemDto.SelectedOptionRequestDto(
                        optionId = o.optionId,
                        groupId = o.groupId,
                        quantity = o.quantity,
                    )
                },
    )

@OptIn(ExperimentalTime::class)
fun OrderItemDto.OrderItemResponseDto.toItems(
    product: Product,
    extras: List<ProductExtra>?,
): Items =
    Items(
        id = Clock.System.now().toEpochMilliseconds(),
        orderItemIds = orderItemIds,
        ordersId = ordersId,
        product = product,
        // The response carries these as strings, so they parse exactly — no cents, no Double.
        unitPrice = Decimal.parse(unitPrice),
        totalPrice = Decimal.parse(finalPrice),
        itemStatus = itemStatus,
        vatClass = vatClass,
        vatRate = vatRate.toDoubleOrNull(),
        customPrice = customPrice,
        // Inbound DTO boundary: discount is still a Double on the wire. Its string form is the
        // shortest representation that round-trips, and the column is DECIMAL(4,2), so this is
        // exact for every value the server can send.
        discount = Decimal.parse(discount.toString()),
        takeAway = takeAway,
        transferred = transferred,
        sideNotes = sideNotes,
        quantity = quantity,
        extras = extras,
        // Map the response snapshot options into client-side selected options. groupId comes from
        // the snapshot so a reloaded line can be re-submitted — the server's resolveSelections
        // rejects a null/unattached group. It stays 0 only for options captured before the column
        // existed, or for legacy flat extras, which never had a group.
        selectedOptions =
            this.extras.orEmpty().map { o ->
                SelectedOption(
                    optionId = o.optionId ?: 0,
                    groupId = o.groupId ?: 0,
                    name = o.name,
                    priceDelta = Decimal.parseOrNull(o.priceDelta) ?: Decimal.ZERO,
                    groupName = o.groupName ?: "",
                    quantity = o.quantity,
                )
            },
    )
