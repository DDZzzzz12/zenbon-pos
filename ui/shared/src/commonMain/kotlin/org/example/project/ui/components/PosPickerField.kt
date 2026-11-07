package org.example.project.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Read-only [PosTextField] that opens a picker (date, time, dropdown, side panel, …) on tap.
 *
 * Consumes click events through the shared interaction source so the underlying text field never
 * grabs focus or shows the keyboard. The actual picker (dialog, sheet, side panel) is mounted by
 * the caller in response to [onClick].
 *
 * [readOnly] shows the value at full contrast with no arrow and never calls [onClick]; use it for
 * a locked value instead of `enabled = false`, which draws grey-on-grey.
 */
@Composable
fun PosPickerField(
    value: String,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = Icons.Default.ExpandMore,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null,
    supportingText: String? = null,
    appearance: PosTextFieldAppearance = LocalPosTextFieldAppearance.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource, enabled, readOnly) {
        if (!enabled || readOnly) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                onClick()
            }
        }
    }

    PosTextField(
        value = value,
        onValueChange = {},
        label = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        placeholder = placeholder,
        trailingIcon =
            trailingIcon?.takeUnless { readOnly }?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        enabled = enabled,
        readOnly = true,
        locked = readOnly,
        isError = isError,
        errorMessage = errorMessage,
        supportingText = supportingText?.let { text -> { Text(text) } },
        interactionSource = interactionSource,
        appearance = appearance,
    )
}
