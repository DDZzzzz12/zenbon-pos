package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one [PosDialog] footer: secondary (Cancel) at the start, primary at the end, stacked full-width
 * (primary on top) when the labels do not fit side by side. A [destructive] primary uses the error colour.
 *
 * A [start] action (an edit dialog's "Löschen") takes the start edge as a red text button and Cancel
 * moves beside the primary at the end: `[Löschen] … [Abbrechen] [Speichern]`; stacked, it goes last.
 */
@Composable
fun PosDialogFooter(
    primary: PosDialogAction,
    secondary: PosDialogAction?,
    destructive: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    start: PosDialogAction? = null,
    startIcon: ImageVector? = null,
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 16.dp),
        ) {
            if (secondary == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FooterPrimaryButton(primary, destructive, Modifier)
                }
            } else if (start != null) {
                val stacked = posDialogFooterStacks(maxWidth, primary.text, secondary.text + start.text)
                if (stacked) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FooterPrimaryButton(primary, destructive, Modifier.fillMaxWidth())
                        FooterSecondaryButton(secondary, Modifier.fillMaxWidth())
                        FooterStartButton(start, startIcon, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FooterStartButton(start, startIcon, Modifier)
                        Spacer(Modifier.weight(1f))
                        FooterSecondaryButton(secondary, Modifier)
                        FooterPrimaryButton(primary, destructive, Modifier)
                    }
                }
            } else if (posDialogFooterStacks(maxWidth, primary.text, secondary.text)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FooterPrimaryButton(primary, destructive, Modifier.fillMaxWidth())
                    FooterSecondaryButton(secondary, Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FooterSecondaryButton(secondary, Modifier)
                    Spacer(Modifier.weight(1f))
                    FooterPrimaryButton(primary, destructive, Modifier)
                }
            }
        }
    }
}

@Composable
private fun FooterPrimaryButton(
    action: PosDialogAction,
    destructive: Boolean,
    modifier: Modifier,
) {
    if (destructive) {
        PosDangerButton(
            text = action.text,
            onClick = action.onClick,
            modifier = modifier,
            enabled = action.enabled,
            isLoading = action.isLoading,
        )
    } else {
        PosButton(
            text = action.text,
            onClick = action.onClick,
            modifier = modifier,
            enabled = action.enabled,
            isLoading = action.isLoading,
        )
    }
}

/** The start action: a text button in the error colour ("Löschen"), 48dp high on touch windows. */
@Composable
private fun FooterStartButton(
    action: PosDialogAction,
    icon: ImageVector?,
    modifier: Modifier,
) {
    PosTextButton(
        text = action.text,
        onClick = action.onClick,
        modifier = modifier,
        enabled = action.enabled,
        icon = icon,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    )
}

@Composable
private fun FooterSecondaryButton(
    action: PosDialogAction,
    modifier: Modifier,
) {
    PosSecondaryButton(
        text = action.text,
        onClick = action.onClick,
        modifier = modifier,
        enabled = action.enabled,
        isLoading = action.isLoading,
    )
}
