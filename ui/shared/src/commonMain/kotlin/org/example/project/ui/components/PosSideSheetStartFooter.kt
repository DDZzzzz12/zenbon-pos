package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A [PosSideSheet] footer with an extra control at the very start (a Löschen button): [start], then
 * the secondary and primary actions at the end. On a [phone] the two actions share the remaining
 * width. Used only when a sheet passes `footerStart`; otherwise the sheet draws the standard
 * [PosDialogFooter].
 */
@Composable
internal fun PosSideSheetStartFooter(
    start: @Composable () -> Unit,
    primary: PosDialogAction,
    secondary: PosDialogAction,
    destructive: Boolean,
    phone: Boolean,
    horizontalPadding: Dp,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            start()
            if (!phone) Spacer(Modifier.weight(1f))
            val actionModifier = if (phone) Modifier.weight(1f) else Modifier
            PosSecondaryButton(
                text = secondary.text,
                onClick = secondary.onClick,
                modifier = actionModifier,
                enabled = secondary.enabled,
                isLoading = secondary.isLoading,
            )
            if (destructive) {
                PosDangerButton(
                    text = primary.text,
                    onClick = primary.onClick,
                    modifier = actionModifier,
                    enabled = primary.enabled,
                    isLoading = primary.isLoading,
                )
            } else {
                PosButton(text = primary.text, onClick = primary.onClick, modifier = actionModifier, enabled = primary.enabled, isLoading = primary.isLoading)
            }
        }
    }
}
