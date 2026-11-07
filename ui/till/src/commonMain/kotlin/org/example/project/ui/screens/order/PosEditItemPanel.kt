package org.example.project.ui.screens.order

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.PriceAuthority
import org.example.project.core.data.models.ChoiceGroup
import org.example.project.core.data.models.ChoiceOption
import org.example.project.core.data.models.Items
import org.example.project.core.data.models.SelectedOption
import org.example.project.core.data.models.orderLineTotal
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.ChoiceGroupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.EditOrderItemKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toSignedDeltaCurrency
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.money.Percentage
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.theme.PosSpacing

@Composable
fun PosEditItemPanel(
    item: Items,
    canSetCustomPrice: Boolean,
    canApplyHighDiscount: Boolean,
    canPriceLines: Boolean,
    onSave: (Items) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    availableGroups: List<ChoiceGroup> = emptyList(),
) {
    val languageManager = LocalLanguageManager.current
    val currentLanguage by languageManager.currentLanguage

    // State management
    var quantity by remember { mutableIntStateOf(item.quantity) }
    // The field edits percent OFF, while Items.discount is the multiplier the server persists.
    var discount by remember {
        mutableStateOf(Percentage.ofFraction(Decimal.ONE - item.discount).toPercent().toPlainString())
    }
    var customPriceEnabled by remember { mutableStateOf(item.customPrice) }
    var customPriceValue by remember {
        mutableStateOf(
            // Raw while editing: reformatting mid-keystroke breaks typing.
            if (item.customPrice) {
                item.unitPrice.toPlainString()
            } else {
                item.product.price.toPlainString()
            },
        )
    }
    var sideNotes by remember { mutableStateOf(item.sideNotes ?: "") }
    var takeaway by remember { mutableStateOf(item.takeAway) }

    // Choice-group selections: groupId -> set of optionIds.
    // Pre-populate from the item's existing selectedOptions when editing,
    // otherwise pre-select the default options of each group.
    var selectedByGroup by remember(availableGroups, item.id) {
        mutableStateOf(initialSelectionsFor(availableGroups, item.selectedOptions))
    }

    // Price calculation. Mirrors the server's order (OrderServiceImpl): the per-unit price is
    // rounded to 2dp AFTER the discount, then multiplied by quantity — see orderLineTotal.
    val basePrice: Decimal? =
        if (customPriceEnabled && canPriceLines) {
            // The keystroke filter below accepts German "16,90", so normalise the separator. A
            // half-typed value ("16.") yields null and simply leaves the total blank until complete.
            Decimal.parseOrNull(customPriceValue.replace(',', '.'))
        } else {
            item.product.price
        }
    val maxDiscount = maxDiscountPercent(canApplyHighDiscount)
    val discountPercent = discountPercentOrNull(discount, maxDiscount)
    // Multiplier, matching Items.discount: 15 % off -> 0.85. Deliberately not inverted.
    // Null when the field does not hold a usable whole percent — see discountPercentOrNull.
    // Where pricing is not offered (an order station, E-2) the line is at list price, whatever the
    // hidden field was seeded with.
    val discountMultiplier =
        if (canPriceLines) discountPercent?.let { Percentage.ofPercent(it).complement().toFraction() } else Decimal.ONE
    // Sum of selected choice-option deltas (per single unit).
    val optionsTotal =
        availableGroups
            .flatMap { group ->
                val picked = selectedByGroup[group.id].orEmpty()
                group.options.filter { it.optionId in picked }
            }.fold(Decimal.ZERO) { acc, o -> acc + o.priceDelta }
    val unitPriceWithExtras = basePrice?.plus(optionsTotal)
    // What Save would commit: null while either input is unusable, which also disables Save below.
    val totalPrice =
        discountMultiplier?.let { multiplier ->
            unitPriceWithExtras?.let { orderLineTotal(it, quantity, multiplier) }
        }
    // What the summary shows. An unusable discount shows the UNDISCOUNTED line, next to the red
    // hint under the field and a disabled Save — not 0,00 €, which read as 100 % off (REG-2).
    val displayedTotal = displayedLineTotal(unitPriceWithExtras, quantity, discountMultiplier)
    val originalTotal = originalLineTotal(item.product.price, item.quantity)

    // Validation: every group must satisfy its min..max selection bounds.
    val groupsValid =
        availableGroups.all { group ->
            val count = selectedByGroup[group.id].orEmpty().size
            count in group.minSelections..group.maxSelections
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(EditOrderItemKeys.title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            PosIconButton(
                icon = Icons.Default.Close,
                contentDescription = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
        }

        // 2. Product info
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.product.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = item.product.price.toFormattedCurrency(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // 3. Quantity
        Text(
            text = str(EditOrderItemKeys.quantity),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PosIconButton(
                icon = Icons.Default.Remove,
                contentDescription = str(AccessibilityKeys.decrease),
                onClick = { if (quantity > 1) quantity -= 1 },
                tint = MaterialTheme.colorScheme.primary,
                size = 40.dp,
            )

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "$quantity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = PosSpacing.sm),
                )
            }

            PosIconButton(
                icon = Icons.Default.Add,
                contentDescription = str(AccessibilityKeys.increase),
                onClick = { quantity += 1 },
                tint = MaterialTheme.colorScheme.primary,
                size = 40.dp,
            )
        }

        HorizontalDivider()

        // 4. Discount % — and 5. custom price below — are not offered where lines may not be priced at
        // all: under the order station's shared account (E-2). The server refuses such a line too
        // (PriceAuthority.mayPriceLinesAs).
        if (canPriceLines) {
            PosTextField(
                value = discount,
                onValueChange = { newValue ->
                    // Shape only: digits plus at most one decimal separator. The semantic checks live
                    // in discountPercentOrNull and surface as the error below, rather than as silently
                    // dropped keystrokes — an integer-only filter freezes a field seeded with "12.5",
                    // which cannot be backspaced through "12.".
                    if (newValue.isEmpty() || DISCOUNT_INPUT_SHAPE.matches(newValue)) {
                        discount = newValue
                    }
                },
                label = str(EditOrderItemKeys.discount),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                isError = discountPercent == null,
                errorMessage =
                    if (discountPercent == null) {
                        str(EditOrderItemKeys.discountWholePercentOnly, maxDiscount)
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 5. Custom Price
        if (canPriceLines && (canSetCustomPrice || item.product.allowCustomPrice)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(EditOrderItemKeys.customPrice),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = customPriceEnabled,
                    onCheckedChange = { customPriceEnabled = it },
                )
            }

            if (customPriceEnabled) {
                PosTextField(
                    value = customPriceValue,
                    onValueChange = { newValue ->
                        // Lenient price-input gate: accept a decimal-in-progress (digits with at most
                        // one separator), so mid-typing states like "12." are not rejected. Parsing to
                        // an exact Decimal happens where the value is used, not here.
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*[.,]?\\d*$"))) {
                            customPriceValue = newValue
                        }
                    },
                    label = str(EditOrderItemKeys.unitPrice),
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 6. Side Notes
        PosTextField(
            value = sideNotes,
            onValueChange = { sideNotes = it },
            label = str(EditOrderItemKeys.sideNotes),
            modifier = Modifier.fillMaxWidth(),
        )

        // 7. Takeaway
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(EditOrderItemKeys.takeaway),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = takeaway,
                onCheckedChange = { takeaway = it },
            )
        }

        // 8. Choice groups section
        if (availableGroups.isNotEmpty()) {
            HorizontalDivider()

            Text(
                text = str(ChoiceGroupKeys.choiceGroups),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            availableGroups.forEach { group ->
                val picked = selectedByGroup[group.id].orEmpty()
                val isUnmet = picked.size < group.minSelections
                val capReached = picked.size >= group.maxSelections

                ChoiceGroupCard(
                    group = group,
                    picked = picked,
                    isUnmet = isUnmet,
                    capReached = capReached,
                    language = currentLanguage,
                    onToggle = { optionId ->
                        selectedByGroup = toggleSelection(selectedByGroup, group, optionId)
                    },
                )
            }
        }

        HorizontalDivider()

        // 9. Price summary
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = str(EditOrderItemKeys.totalPrice),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Original price (strikethrough if changed)
                if (displayedTotal != originalTotal) {
                    Text(
                        text = originalTotal.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }

                // New calculated total
                Text(
                    text = (displayedTotal ?: Decimal.ZERO).toFormattedCurrency(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 10. Save button
        PosButton(
            text = str(CommonKeys.save),
            // A rejected discount blocks the save as well as showing the error; without this the
            // panel would still be able to commit a line whose total it refused to compute.
            enabled = groupsValid && discountMultiplier != null,
            onClick = {
                val effectiveCustomPrice =
                    canPriceLines && customPriceEnabled && (canSetCustomPrice || item.product.allowCustomPrice)

                val selectedOptions = buildSelectedOptions(availableGroups, selectedByGroup)

                onSave(
                    item.copy(
                        quantity = quantity,
                        // Unreachable while null — the button above is disabled — but keeping the
                        // item's existing multiplier is the only safe fallback if that ever slips.
                        discount = discountMultiplier ?: item.discount,
                        customPrice = effectiveCustomPrice,
                        // The base WITHOUT extras or option deltas, whichever branch produced it —
                        // `basePrice` is already the custom price or the list price. Those deltas
                        // live in `extras`/`selectedOptions` below and are re-added by
                        // `Items.extrasDelta`, mirroring the server: `OrderItemTable.unitPrice`
                        // likewise holds the bare base, and createOrder computes
                        // `(unitPrice + extrasDelta) * discount` from it. Folding them in here
                        // would make the server add them a SECOND time on the custom-price branch,
                        // which is the one branch where it uses the value the client sends.
                        unitPrice = basePrice ?: item.unitPrice,
                        totalPrice = totalPrice ?: Decimal.ZERO,
                        sideNotes = sideNotes.ifBlank { null },
                        takeAway = takeaway,
                        selectedOptions = selectedOptions,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A single choice-group card: localized header with cardinality/pick-rule labels and
 * a required-unmet indicator, followed by radio rows (single-select) or checkbox rows
 * (multi-select with cap enforcement).
 */
@Composable
private fun ChoiceGroupCard(
    group: ChoiceGroup,
    picked: Set<Int>,
    isUnmet: Boolean,
    capReached: Boolean,
    language: Language,
    onToggle: (Int) -> Unit,
) {
    val pickRule =
        when {
            group.isSingleSelect -> str(ChoiceGroupKeys.pickOne)
            group.maxSelections >= group.options.size -> str(ChoiceGroupKeys.pickAny)
            else -> str(ChoiceGroupKeys.pickUpTo, group.maxSelections)
        }
    val cardinality = if (group.isRequired) str(ChoiceGroupKeys.required) else str(ChoiceGroupKeys.optional)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.localizedName(language),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color =
                        if (isUnmet) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                Text(
                    text = "$cardinality · $pickRule",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isUnmet) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }

        PosCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            variant = PosCardVariant.Plain,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            body {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(group.options) { option ->
                        val isSelected = option.optionId in picked
                        // Multi-select unchecked rows are disabled once the cap is reached.
                        val isEnabled = group.isSingleSelect || isSelected || !capReached

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isEnabled) {
                                            Modifier.clickable { onToggle(option.optionId) }
                                        } else {
                                            Modifier
                                        },
                                    ).padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (group.isSingleSelect) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onToggle(option.optionId) },
                                    )
                                } else {
                                    Checkbox(
                                        checked = isSelected,
                                        enabled = isEnabled,
                                        onCheckedChange = { onToggle(option.optionId) },
                                    )
                                }
                                Text(
                                    text = option.localizedName(language),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color =
                                        if (isEnabled) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }

                            if (!option.priceDelta.isZero) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        text = option.priceDelta.toSignedDeltaCurrency(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }

                        if (option != group.options.last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a percent looks like while it is being typed: up to three digits, optionally followed by a
 * separator and up to two more. Wide enough to type through an intermediate "12." and to edit a
 * seeded fractional value; the value itself is judged by [discountPercentOrNull].
 */
private val DISCOUNT_INPUT_SHAPE = Regex("""\d{0,3}([.,]\d{0,2})?""")

/**
 * Whether this operator may exceed the 20 % cap — the till half of `PriceAuthority.mayApplyDiscount`.
 *
 * Reads [PriceAuthority.STEEP_DISCOUNT_AUTHORITY] (`DISCOUNT_GRANT`), the value the server checks.
 * It used to reuse the custom-price gate (`PRICE_OVERRIDE`), so a group holding only that was
 * offered discounts the server then refused with 403 (T5-5). The manager-PIN step-up lends
 * `DISCOUNT_GRANT` (it is in `ElevationScope.TILL_OVERRIDES`), so an elevated session unlocks it.
 */
internal fun canApplyHighDiscount(policy: AccessPolicy): Boolean = policy.allows(PriceAuthority.STEEP_DISCOUNT_AUTHORITY)

/** The highest discount this operator may grant, in whole percent. */
internal fun maxDiscountPercent(canApplyHighDiscount: Boolean): Int = if (canApplyHighDiscount) 100 else 20

/**
 * The discount field's value as a whole percent, or null when it cannot be used.
 *
 * **Whole percents only, and rejected rather than rounded — deliberately.** The field edits
 * percent OFF, but what is persisted is the MULTIPLIER, in `OrderItemTable.discount`, a
 * `DECIMAL(4,2)`. 12.5 % off is multiplier `0.875`: scale 3, which that column cannot hold.
 * Accepting it would mean either silently rounding to `0.88` — charging the customer something
 * other than what the cashier typed, on a figure that ends up on a fiscal receipt — or promising
 * precision the database does not have. Do NOT "improve" this into silent rounding; making
 * fractional discounts real is a server-side column change, not a client one.
 *
 * The previous parse was `discount.toIntOrNull() ?: 0`, so "12.5" (and "12,5", and anything
 * mistyped) became 0 % off: the discount vanished with no message at all.
 *
 * The whole-percent test is on VALUE, not scale — "15.0" is a perfectly good 15 %.
 */
internal fun discountPercentOrNull(
    input: String,
    maxPercent: Int,
): Decimal? {
    val trimmed = input.trim()
    // An empty field means no discount, not a typo.
    if (trimmed.isEmpty()) return Decimal.ZERO
    // The keystroke filter accepts a German decimal comma, so normalise before parsing.
    val parsed = Decimal.parseOrNull(trimmed.replace(',', '.')) ?: return null
    if (parsed < Decimal.ZERO || parsed > Decimal.of(maxPercent)) return null
    val whole = parsed.setScale(0, MoneyRounding.DOWN)
    return if (whole == parsed) whole else null
}

/**
 * The strikethrough baseline in the price summary: this line at list price and no discount.
 *
 * Must run through [orderLineTotal], the same helper as the total it is compared against. The
 * panel renders the struck-through original only when `totalPrice != originalTotal`, and
 * `totalPrice` discounts and rounds the unit price to cents BEFORE multiplying by quantity,
 * mirroring the server's one-row-per-unit `finalPrice`. Rounding the whole line here instead put
 * the two a cent apart at quantity >= 2 whenever the server sent a scale-4 price (DECIMAL(19,4)),
 * so an item nobody had touched rendered with its price struck through.
 */
internal fun originalLineTotal(
    listPrice: Decimal,
    quantity: Int,
): Decimal = orderLineTotal(listPrice, quantity, Decimal.ONE)

/**
 * The line total the price summary shows, or null while the unit price itself is unusable (a
 * half-typed custom price).
 *
 * An unusable discount ([discountMultiplier] null) shows the line UNDISCOUNTED rather than nothing:
 * the panel used to render `totalPrice ?: 0`, so typing 25 under a 20 % cap showed 10,00 € struck
 * through next to „0,00 €" — as if the line were free (REG-2). The field's red hint and the disabled
 * Save already say the discount was not taken; the total must not claim otherwise.
 */
internal fun displayedLineTotal(
    unitPriceWithExtras: Decimal?,
    quantity: Int,
    discountMultiplier: Decimal?,
): Decimal? = unitPriceWithExtras?.let { orderLineTotal(it, quantity, discountMultiplier ?: Decimal.ONE) }

/**
 * Whether the edit panel offers line pricing — the discount field and the custom-price toggle — at
 * all: the client half of the server's `PriceAuthority.mayPriceLinesAs`. [isStation] is
 * `TokenStore.isStation` — true only for the order station's own session, which is exactly the
 * station's shared account the server refuses (a person's login clears the marker). That account
 * gets neither control (E-2); a person keeps both on any device and the server decides.
 *
 * Asked through the station marker, not the session's role: UI does not branch on a role
 * (`NoRoleGatesInScreensTest`). `EditPanelStationPricingTest` pins that the station session's role
 * is one `mayPriceLinesAs` refuses.
 */
internal fun linePricingOffered(isStation: Boolean): Boolean = !isStation

/** Localized group name following the German-default convention used across the app. */
private fun ChoiceGroup.localizedName(language: Language): String =
    when (language) {
        Language.GERMAN -> name
        Language.ENGLISH -> enName ?: name
        Language.CHINESE -> chName ?: name
    }

/** Localized option name following the German-default convention used across the app. */
private fun ChoiceOption.localizedName(language: Language): String =
    when (language) {
        Language.GERMAN -> name
        Language.ENGLISH -> enName ?: name
        Language.CHINESE -> chName ?: name
    }

/**
 * Builds the initial groupId -> optionIds selection map.
 * Pre-populates from existing selected options (edit mode), falling back to default options.
 */
private fun initialSelectionsFor(
    groups: List<ChoiceGroup>,
    existing: List<SelectedOption>,
): Map<Int, Set<Int>> {
    if (groups.isEmpty()) return emptyMap()
    return groups.associate { group ->
        val fromExisting =
            existing
                .filter { sel -> group.options.any { it.optionId == sel.optionId } }
                .map { it.optionId }
                .toSet()
        val selection =
            if (fromExisting.isNotEmpty()) {
                fromExisting
            } else {
                group.options
                    .filter { it.isDefault }
                    .map { it.optionId }
                    .toSet()
            }
        group.id to selection
    }
}

/** Toggles an option within a group, honoring single-select replacement and max-cap limits. */
private fun toggleSelection(
    current: Map<Int, Set<Int>>,
    group: ChoiceGroup,
    optionId: Int,
): Map<Int, Set<Int>> {
    val groupSelection = current[group.id].orEmpty()
    val newSelection =
        when {
            group.isSingleSelect -> setOf(optionId)
            optionId in groupSelection -> groupSelection - optionId
            groupSelection.size >= group.maxSelections -> groupSelection // cap reached, ignore
            else -> groupSelection + optionId
        }
    return current + (group.id to newSelection)
}

/** Materializes the selection map into [SelectedOption]s, looking up name/delta/groupName. */
private fun buildSelectedOptions(
    groups: List<ChoiceGroup>,
    selectedByGroup: Map<Int, Set<Int>>,
): List<SelectedOption> =
    groups.flatMap { group ->
        val picked = selectedByGroup[group.id].orEmpty()
        group.options
            .filter { it.optionId in picked }
            .map { option ->
                SelectedOption(
                    optionId = option.optionId,
                    groupId = group.id,
                    name = option.name,
                    priceDelta = option.priceDelta,
                    groupName = group.name,
                )
            }
    }
