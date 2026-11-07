package org.example.project.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Matches a `FilterChip` row, so a flat field can sit in a filter bar without towering over it. */
internal val POS_FLAT_FIELD_MIN_HEIGHT: Dp = 40.dp

/**
 * Minimum height of the [PosFlatField] box below. Defaults to [POS_FLAT_FIELD_MIN_HEIGHT]; only
 * `PosFilterPicker` raises it, to the 48dp touch target on non-expanded windows, because a picker
 * field is the tap target itself (its label above the box is not clickable).
 */
internal val LocalPosFlatFieldMinHeight = staticCompositionLocalOf { POS_FLAT_FIELD_MIN_HEIGHT }

/**
 * The flat input surface shared by [PosSearchBar] and [PosTextField]'s
 * [PosTextFieldAppearance.Flat] — a rounded tonal container with an inline leading icon and a
 * [BasicTextField], and none of Material's outline, indicator line or floating-label notch.
 *
 * Extracted rather than duplicated: the two were the same twenty lines, and a field that looks like
 * a search box on one screen and like a form control on another is the drift this exists to stop.
 *
 * It renders **no label of its own.** A label belongs above this box, not notched into its border —
 * that notch is the specific thing this treatment replaces. [PosSearchBar] passes none at all,
 * because a magnifier plus a placeholder already say what the field is.
 */
@Composable
internal fun PosFlatField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    lockedLook: Boolean = false,
    isError: Boolean = false,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    interactionSource: MutableInteractionSource? = null,
) {
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            isError -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    val hintColor =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val readOnlyLook = lockedLook && enabled
    // A read-only box (locked value, or a picker that opens a dropdown) never takes the caret, so it
    // gets no focus border either.
    val showFocus = focused && !readOnly
    val resolvedTextStyle = (textStyle ?: MaterialTheme.typography.bodyMedium).copy(color = contentColor)

    // An explicit container step, NOT tonalElevation. Tonal elevation is relative to whatever
    // surface it sits on, so inside an already-elevated panel — a side drawer, a dialog — the field
    // resolved to almost the same colour as its parent and a column of them read as one flat block
    // with floating labels. surfaceContainerHighest is a fixed step, and the hairline guarantees an
    // edge even where the fill and the parent are close.
    Surface(
        modifier =
            modifier
                .defaultMinSize(minHeight = LocalPosFlatFieldMinHeight.current)
                .border(
                    width = if (showFocus && !isError) 2.dp else 1.dp,
                    color =
                        when {
                            isError -> MaterialTheme.colorScheme.error
                            showFocus -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    shape = MaterialTheme.shapes.small,
                ),
        shape = MaterialTheme.shapes.small,
        color =
            if (readOnlyLook) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = if (leadingIcon != null) 12.dp else 14.dp,
                    end = if (trailing != null) 4.dp else 12.dp,
                    // A single line is centred by the min height; wrapped text needs its own margin or
                    // the first and last lines touch the box (read-only Beschreibung, UI review nit).
                    top = if (singleLine) 0.dp else 10.dp,
                    bottom = if (singleLine) 0.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = hintColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (prefix != null) {
                AffixSlot(hintColor, prefix, startSide = true)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = resolvedTextStyle,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                interactionSource = source,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = resolvedTextStyle.copy(color = hintColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (singleLine && !focused && value.isNotEmpty()) {
                        // Unfocused: show an ellipsised copy; the real field stays underneath, invisible,
                        // so focus and taps still reach it.
                        Box {
                            Box(Modifier.alpha(0f)) { inner() }
                            Text(
                                visualTransformation.filter(AnnotatedString(value)).text,
                                style = resolvedTextStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        inner()
                    }
                },
            )
            if (suffix != null) {
                AffixSlot(hintColor, suffix, startSide = false)
            }
            trailing?.invoke()
        }
    }
}

/** A prefix/suffix next to the input, in the muted body style the Material slots use. */
@Composable
private fun AffixSlot(
    color: Color,
    content: @Composable () -> Unit,
    startSide: Boolean,
) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
    ) {
        Box(Modifier.padding(start = if (startSide) 0.dp else 4.dp, end = if (startSide) 4.dp else 0.dp)) { content() }
    }
}
