package org.example.project.ui.components.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/**
 * The fixed footer of every editing surface — drawer, full-screen page, settings group that saves
 * as a whole (Zenbon "SaveBar" pattern). Place it below the scrolling content, never inside it.
 *
 * Speichern is enabled only while [isDirty]; while [isSaving] it shows its spinner and the bar keeps
 * its place. After a failed save, [errorCount] > 0 shows "2 Felder prüfen" on wide windows; success
 * is a toast, not a message here. On phone width the two buttons share the width.
 *
 * Typically fed straight from a [PosFormState]: `isDirty = form.isDirty, errorCount = form.errorCount`.
 */
@Composable
fun PosSaveBar(
    isDirty: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    errorCount: Int = 0,
    saveLabel: String = str(CommonKeys.save),
) {
    val compact = LocalPosLayout.current.isCompact
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = LocalPosLayout.current.screenPadding, vertical = PosSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                PosButton(
                    text = saveLabel,
                    onClick = onSave,
                    enabled = isDirty,
                    isLoading = isSaving,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ErrorSummary(errorCount, Modifier.weight(1f))
                PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onCancel, height = 40.dp)
                PosButton(text = saveLabel, onClick = onSave, enabled = isDirty, isLoading = isSaving, height = 40.dp)
            }
        }
    }
}

@Composable
private fun ErrorSummary(
    errorCount: Int,
    modifier: Modifier,
) {
    val text =
        when {
            errorCount <= 0 -> ""
            errorCount == 1 -> str(PosPatternKeys.formErrorCountOne)
            else -> str(PosPatternKeys.formErrorCountMany, errorCount)
        }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error,
    )
}
