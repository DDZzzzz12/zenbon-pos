package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.ActionReasonKeys
import org.example.project.core.presentation.strings.str

/**
 * Wrapping row of single-select reason chips; the details field appears only while "Other" is chosen.
 * Wrapping (not a horizontal scroller) so a phone-width dialog needs no sideways scrolling.
 */
@Composable
fun ReasonChips(
    state: ReasonSelectionState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = str(ActionReasonKeys.reasonHeading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.reasons.forEach { reason ->
                FilterChip(
                    selected = state.selected == reason,
                    onClick = { state.select(reason) },
                    label = { Text(str(reason.labelKey)) },
                    modifier = Modifier.heightIn(min = 36.dp),
                )
            }
        }
        if (state.selected?.isOther == true) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            PosTextField(
                value = state.otherText,
                onValueChange = state::onOtherTextChange,
                label = str(ActionReasonKeys.otherDetails),
                singleLine = false,
                maxLines = 3,
                focusRequester = focusRequester,
            )
        }
        if (state.showRequiredHint) {
            Text(
                text = str(ActionReasonKeys.reasonRequired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
