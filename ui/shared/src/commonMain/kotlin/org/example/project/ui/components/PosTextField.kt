package org.example.project.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardType
import org.example.project.core.utils.isDesktop
import org.example.project.money.Decimal
import org.example.project.money.MoneyException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class PosTextFieldType {
    Default,
    Price,
    Percentage,
    Integer,
}

/** A price always settles to at least two decimals, so "16.9" still becomes "16.90" on blur. */
private const val MIN_PRICE_SCALE = 2

/**
 * A percentage keeps exactly the scale it was typed or seeded with: a whole VAT rate stays "19",
 * a fractional discount keeps every digit. The floor is set from intent rather than a caller —
 * [PosTextFieldType.Percentage] has no live call site yet — because unlike a price a percentage
 * must never be padded up ("19" is not "19.00"), and the defect being fixed is that it was also
 * truncated down.
 */
private const val MIN_PERCENT_SCALE = 0

/** Any ASCII letter rejects the whole value, rather than being stripped out of the middle of it. */
private val NUMERIC_LETTER = Regex("[a-zA-Z]")

/** Currency and percent symbols, spaces and the like. Applied after the comma has been folded away. */
private val NUMERIC_NON_NUMERIC = Regex("[^0-9.+-]")

/**
 * Canonical text for a numeric field on blur, or null when [raw] is not a number.
 *
 * Pure and Compose-free so the rule is unit-testable — it previously lived inside an
 * `onFocusChanged` lambda, one copy per field type, which is how the truncation went unnoticed.
 *
 * Parses with `:platform-money`, not `String.toCents()`. Cents pin the scale at 2 and TRUNCATE, so a value
 * carrying the server's `DECIMAL(19,4)` precision ("16.8950") was rewritten to "16.89" the moment
 * the edit dialog seeded the field — losing half a cent on save even when the user only changed the
 * name. [minScale] is the fewest decimals the result may carry (two for a price, zero for a
 * percentage); the reformat pads UP to it but never drops digits the value actually has, so
 * "16.895" survives and a whole "19" is never forced to "19.00".
 */
private fun normalizeDecimalInput(
    raw: String,
    minScale: Int,
): String? {
    // A letter is a typo, not a symbol: reject before stripping, or "12abc" would be salvaged
    // to "12.00". This mirrors the String.toCents() path being replaced.
    if (NUMERIC_LETTER.containsMatchIn(raw)) return null
    // Decimal.parse is strict ASCII "[+-]?digits[.digits]" and rejects the German "16,90" that
    // the Decimal keyboard and desktop paste both admit, so fold the separator first — the same
    // move PosEditItemPanel makes. Symbols and spaces are then stripped, as toCents() did.
    val cleaned = raw.trim().replace(',', '.').replace(NUMERIC_NON_NUMERIC, "")
    // A keypad user writes half a unit as ".5", and toCents() read that as 0.50, but the parser
    // demands an integer part. A bare "." still has no digits and stays rejected.
    val withIntegerPart =
        when {
            cleaned.startsWith(".") -> "0$cleaned"
            cleaned.startsWith("-.") || cleaned.startsWith("+.") ->
                cleaned.take(1) + "0" + cleaned.drop(1)
            else -> cleaned
        }
    val parsed = Decimal.parseOrNull(withIntegerPart) ?: return null
    return try {
        parsed.setScale(maxOf(minScale, parsed.scale)).toPlainString()
    } catch (e: MoneyException) {
        // Unreachable for anything parse() accepts today (18 integer digits + scale 12 is exactly
        // the 30-digit envelope), but this runs in a focus callback: returning beats throwing.
        null
    }
}

/**
 * Canonical text for a [PosTextFieldType.Price] field on blur, or null when [raw] is not a price.
 * Floors at two decimals, so "16.9" settles to "16.90" for the two-decimal call sites, while
 * "16.895" keeps its precision. See [normalizeDecimalInput] for why it does not use `toCents()`.
 */
internal fun normalizePriceInput(raw: String): String? = normalizeDecimalInput(raw, MIN_PRICE_SCALE)

/**
 * Canonical text for a [PosTextFieldType.Percentage] field on blur, or null when [raw] is not a
 * number.
 *
 * The same pipeline as [normalizePriceInput] with a floor of zero: this branch round-tripped
 * through `String.toCents()` too, so "19.005" was truncated to "19" and any third decimal a
 * fractional discount carried was lost. A whole VAT rate stays "19" rather than padded to "19.00".
 */
internal fun normalizePercentInput(raw: String): String? = normalizeDecimalInput(raw, MIN_PERCENT_SCALE)

/**
 * The project's text field. The appearance comes from [LocalPosTextFieldAppearance] unless a call
 * site overrides it, so adminApp renders flat and composeApp outlined from the same call.
 *
 * **Placing one in a `Row`.** In the [PosTextFieldAppearance.Flat] appearance the label is a line of
 * text *above* the input box, so the composable is taller than the box. A `Row` with
 * `verticalAlignment = Alignment.CenterVertically` then centres label-plus-box against its
 * neighbours and the box itself lands below every button beside it. Two ways out, in order of
 * preference:
 *  - a filter or search control in a toolbar has no business carrying a label — use [PosSearchBar],
 *    whose prompt is a placeholder inside the box;
 *  - a labelled field genuinely belongs in the row — use `Alignment.Bottom`, which lines the box up
 *    with the 40 dp controls next to it.
 *
 * Neither applies in a `Column`, which is where form fields normally live.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun PosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    /** The value is displayed, not editable, and the reason is shown. Implies the read-only look; pair with [readOnly]. */
    locked: Boolean = false,
    /** Borderless only: entering the field puts the caret at the end of the value instead of the start. */
    caretAtEndOnFocus: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null,
    textFieldType: PosTextFieldType = PosTextFieldType.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource? = null,
    colors: TextFieldColors? = null,
    appearance: PosTextFieldAppearance = LocalPosTextFieldAppearance.current,
) {
    val focusManager = LocalFocusManager.current
    var validationError by remember { mutableStateOf(false) }

    val effectiveKeyboardType =
        remember(keyboardType, textFieldType) {
            if (keyboardType != KeyboardType.Text) {
                keyboardType
            } else {
                when (textFieldType) {
                    PosTextFieldType.Price, PosTextFieldType.Percentage -> KeyboardType.Decimal
                    PosTextFieldType.Integer -> KeyboardType.Number
                    PosTextFieldType.Default -> KeyboardType.Text
                }
            }
        }

    val keyboardActions =
        remember(imeAction) {
            KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { focusManager.clearFocus() },
                onSearch = { focusManager.clearFocus() },
                onSend = { focusManager.clearFocus() },
                onGo = { focusManager.clearFocus() },
            )
        }

    val isDesktopPlatform = isDesktop()
    val virtualKeyboard = if (isDesktopPlatform) LocalVirtualKeyboard.current else null

    // Opaque per-instance token for the virtual-keyboard registry: it only has to be unique per
    // composable instance and stable across recompositions. Uses the multiplatform Uuid rather than
    // java.util.UUID, which is JVM-only and does not compile for this module's wasmJs target.
    @OptIn(ExperimentalUuidApi::class)
    val fieldId = remember { Uuid.random().toString() }
    val bringIntoViewRequester = if (isDesktopPlatform) remember { BringIntoViewRequester() } else null
    val coroutineScope = if (isDesktopPlatform) rememberCoroutineScope() else null

    val vkType =
        remember(effectiveKeyboardType) {
            when (effectiveKeyboardType) {
                KeyboardType.Number, KeyboardType.Decimal,
                KeyboardType.Phone, KeyboardType.NumberPassword,
                -> VirtualKeyboardType.NUMERIC
                else -> VirtualKeyboardType.ALPHABETIC
            }
        }

    if (isDesktopPlatform && virtualKeyboard != null) {
        LaunchedEffect(value) { virtualKeyboard.updateValue(fieldId, value) }
        DisposableEffect(fieldId) { onDispose { virtualKeyboard.unregisterField(fieldId) } }
        // Clear focus when keyboard is dismissed so tapping the field re-triggers it
        // Only on a visible -> hidden transition: the first run sees "hidden" too and would otherwise
        // drop the focus of a field that was just focused by a click-to-edit.
        var keyboardWasVisible by remember { mutableStateOf(virtualKeyboard.isVisible) }
        LaunchedEffect(virtualKeyboard.isVisible) {
            val visible = virtualKeyboard.isVisible
            if (keyboardWasVisible && !visible) {
                focusManager.clearFocus()
            }
            keyboardWasVisible = visible
        }
    }

    val currentValue = remember { mutableStateOf(value) }
    currentValue.value = value
    val currentOnValueChange = remember { mutableStateOf(onValueChange) }
    currentOnValueChange.value = onValueChange

    val onFocusLostValidation: () -> Unit =
        remember(textFieldType) {
            {
                val trimmed = currentValue.value.trim()
                if (trimmed.isEmpty()) {
                    validationError = false
                    return@remember
                }
                val normalized = trimmed.replace(",", ".")
                when (textFieldType) {
                    PosTextFieldType.Default -> {
                        // Only when trimming changed something, like every other branch. Calling it
                        // unconditionally re-sent the unchanged text on every focus loss — and the
                        // first focus event fires when the field is ATTACHED — so a caller that reads
                        // onValueChange as "the operator typed" was told so without a keystroke: the
                        // Z-Bon dialog's count field then stopped following the expected cash and
                        // counted a stale figure (F64).
                        if (trimmed != currentValue.value) {
                            currentOnValueChange.value(trimmed)
                        }
                    }

                    PosTextFieldType.Integer -> {
                        val number = normalized.toDoubleOrNull()
                        if (number == null) {
                            validationError = true
                        } else {
                            validationError = false
                            val formatted = number.toLong().toString()
                            if (formatted != currentValue.value) {
                                currentOnValueChange.value(formatted)
                            }
                        }
                    }

                    PosTextFieldType.Price -> {
                        // Prices are normalised by the helper below rather than from `normalized`:
                        // it owns the whole pipeline (separator, symbols, parse, reformat) so the
                        // rule is reachable from a unit test.
                        val formatted = normalizePriceInput(trimmed)
                        if (formatted == null) {
                            validationError = true
                        } else {
                            validationError = false
                            if (formatted != currentValue.value) {
                                currentOnValueChange.value(formatted)
                            }
                        }
                    }

                    PosTextFieldType.Percentage -> {
                        // Normalised by the helper for the same reason as Price: it owns the whole
                        // pipeline (separator, symbols, parse, reformat) so the rule is reachable from
                        // a unit test, and it parses with :platform-money instead of the truncating toCents().
                        val formatted = normalizePercentInput(trimmed)
                        if (formatted == null) {
                            validationError = true
                        } else {
                            validationError = false
                            if (formatted != currentValue.value) {
                                currentOnValueChange.value(formatted)
                            }
                        }
                    }
                }
            }
        }

    var baseModifier = Modifier.fillMaxWidth()
    if (focusRequester != null) {
        baseModifier = baseModifier.focusRequester(focusRequester)
    }

    val fieldModifier =
        if (isDesktopPlatform && virtualKeyboard != null && !readOnly) {
            baseModifier
                .bringIntoViewRequester(bringIntoViewRequester!!)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        virtualKeyboard.registerField(fieldId, value, currentOnValueChange.value, vkType)
                        coroutineScope!!.launch { bringIntoViewRequester.bringIntoView() }
                    } else {
                        virtualKeyboard.unregisterField(fieldId)
                        onFocusLostValidation()
                    }
                }
        } else if (textFieldType != PosTextFieldType.Default) {
            baseModifier.onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    onFocusLostValidation()
                }
            }
        } else {
            baseModifier
        }

    val activeTfv =
        if (isDesktopPlatform && virtualKeyboard != null && !readOnly) {
            virtualKeyboard.textFieldValue.takeIf { virtualKeyboard.isActiveField(fieldId) }
        } else {
            null
        }

    val commonLeadingIcon: @Composable (() -> Unit)? =
        if (leadingIcon != null) {
            { Icon(imageVector = leadingIcon, contentDescription = null) }
        } else {
            null
        }
    val commonPlaceholder: @Composable (() -> Unit)? =
        if (placeholder != null) {
            // Shrink-to-fit so long placeholders don't wrap to a second row.
            { PosAutoSizeText(text = placeholder) }
        } else {
            null
        }
    val commonIsError = isError || validationError || errorMessage != null
    val commonKeyboardOptions =
        KeyboardOptions(
            keyboardType = effectiveKeyboardType,
            imeAction = imeAction,
        )
    val lockedContainer = MaterialTheme.colorScheme.surfaceContainerLow
    val commonColors =
        colors ?: if (appearance != PosTextFieldAppearance.Borderless) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                // A locked value must not look like an empty input waiting for text.
                focusedContainerColor = if (locked) lockedContainer else Color.Unspecified,
                unfocusedContainerColor = if (locked) lockedContainer else Color.Unspecified,
                errorContainerColor = if (locked) lockedContainer else Color.Unspecified,
            )
        } else {
            // Borderless variant for dense contexts (e.g. table cells): no container fill and no
            // indicator line, so the cell reads as plain text and only the caret/selection shows on edit.
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = MaterialTheme.colorScheme.error,
            )
        }

    val displayValue = activeTfv?.text ?: value
    // Locale separator for numeric fields (admin21 NOTE-e21): display only, the value stays canonical.
    val decimalSeparator = LocalDecimalInputSeparator.current
    val effectiveVisualTransformation =
        remember(visualTransformation, decimalSeparator, textFieldType) {
            val numeric = textFieldType == PosTextFieldType.Price || textFieldType == PosTextFieldType.Percentage
            if (numeric && visualTransformation == VisualTransformation.None && decimalSeparator != null && decimalSeparator != '.') {
                DecimalSeparatorVisualTransformation(decimalSeparator)
            } else {
                visualTransformation
            }
        }
    val handleValueChange: (String) -> Unit = { newText ->
        if (validationError) validationError = false
        if (activeTfv != null) {
            virtualKeyboard!!.onTextFieldValueChange(activeTfv.copy(text = newText))
        } else {
            onValueChange(newText)
        }
    }
    // Shrink-to-fit so long labels don't wrap to a second row.
    val fieldLabel: @Composable () -> Unit = { PosAutoSizeText(text = label) }

    Column(modifier = modifier) {
        if (appearance == PosTextFieldAppearance.Flat) {
            // The label sits above the box rather than notched into its border — that notch is the
            // specific thing this appearance drops. Skipped when blank, so a labelless flat field
            // does not reserve an empty row.
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (commonIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.padding(start = 0.dp, bottom = 4.dp),
                )
            }
            PosFlatField(
                value = displayValue,
                onValueChange = handleValueChange,
                modifier = fieldModifier,
                placeholder = placeholder,
                leadingIcon = leadingIcon,
                trailing = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                enabled = enabled,
                readOnly = readOnly,
                lockedLook = locked,
                isError = commonIsError,
                textStyle = textStyle,
                keyboardOptions = commonKeyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = effectiveVisualTransformation,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = interactionSource,
            )
            // The outlined and borderless variants hand supportingText to Material's own slot; the
            // flat box has no such slot, so it is drawn here, under the box. Without this the hint
            // was silently dropped in adminApp (e2e 10.8: the permission-group Code field's "Wird
            // gespeichert als: …" never showed).
            if (supportingText != null) {
                FlatSupportingText(isError = commonIsError, content = supportingText)
            }
        } else if (appearance == PosTextFieldAppearance.Outlined) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = handleValueChange,
                label = fieldLabel,
                placeholder = commonPlaceholder,
                leadingIcon = commonLeadingIcon,
                trailingIcon = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                supportingText = supportingText,
                enabled = enabled,
                readOnly = readOnly,
                isError = commonIsError,
                textStyle = textStyle,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = commonKeyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = effectiveVisualTransformation,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = interactionSource,
                colors = commonColors,
                modifier = fieldModifier,
            )
        } else if (caretAtEndOnFocus && activeTfv == null) {
            var fieldValue by remember { mutableStateOf(TextFieldValue(displayValue, TextRange(displayValue.length))) }
            val shownValue =
                if (fieldValue.text == displayValue) {
                    fieldValue
                } else {
                    TextFieldValue(displayValue, TextRange(displayValue.length))
                }
            TextField(
                value = shownValue,
                onValueChange = {
                    fieldValue = it
                    handleValueChange(it.text)
                },
                // A floating label reserves an extra row of height; skip it when empty (table cells)
                // so the borderless field stays compact.
                label = if (label.isEmpty()) null else fieldLabel,
                placeholder = commonPlaceholder,
                leadingIcon = commonLeadingIcon,
                trailingIcon = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                supportingText = supportingText,
                enabled = enabled,
                readOnly = readOnly,
                isError = commonIsError,
                textStyle = textStyle,
                keyboardOptions = commonKeyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = effectiveVisualTransformation,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = interactionSource,
                colors = commonColors,
                modifier = fieldModifier,
            )
        } else {
            TextField(
                value = displayValue,
                onValueChange = handleValueChange,
                // A floating label reserves an extra row of height; skip it when empty (table cells)
                // so the borderless field stays compact.
                label = if (label.isEmpty()) null else fieldLabel,
                placeholder = commonPlaceholder,
                leadingIcon = commonLeadingIcon,
                trailingIcon = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                supportingText = supportingText,
                enabled = enabled,
                readOnly = readOnly,
                isError = commonIsError,
                textStyle = textStyle,
                keyboardOptions = commonKeyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = effectiveVisualTransformation,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = interactionSource,
                colors = commonColors,
                modifier = fieldModifier,
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * The supporting line under a [PosTextFieldAppearance.Flat] box, styled the way Material styles its
 * own `supportingText` slot: small, muted, and in the error colour while the field is in error.
 */
@Composable
private fun FlatSupportingText(
    isError: Boolean,
    content: @Composable () -> Unit,
) {
    val color =
        if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    CompositionLocalProvider(
        LocalContentColor provides color,
        LocalTextStyle provides MaterialTheme.typography.bodySmall,
    ) {
        Box(modifier = Modifier.padding(start = 0.dp, top = 4.dp)) { content() }
    }
}
