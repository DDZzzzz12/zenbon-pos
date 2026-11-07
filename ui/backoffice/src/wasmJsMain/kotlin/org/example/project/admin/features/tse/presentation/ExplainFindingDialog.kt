package org.example.project.admin.features.tse.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField

/**
 * Explains one finding, or corrects the explanation it already carries.
 *
 * Submit stays disabled while the text is blank — the client half of the guard the ViewModel and the
 * server both enforce through `CreateRequestDto.validationError()`.
 *
 * The categories render as their enum names on purpose: they are the identifiers written into the
 * register and read back out of the CSV, and an auditor matching a row to a category must see the
 * same token in both places.
 *
 * No `verticalScroll` here — [PosDialog] already scrolls its body.
 */
@Composable
fun ExplainFindingDialog(
    finding: TseExplanationDto.FindingDto,
    current: TseExplanationDto.ExplanationDto?,
    explainError: StringKey?,
    isBusy: Boolean,
    onSubmit: (TseExplanationCategory, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(current?.category ?: TseExplanationCategory.OTHER) }
    var text by remember { mutableStateOf(current?.text.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TseFindingsExplain),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.TseFindingsExplain),
                onClick = { onSubmit(category, text) },
                enabled = !isBusy && text.isNotBlank(),
                isLoading = isBusy,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = findingLabel(finding),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = finding.tseSerialHex,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box {
                PosPickerField(
                    value = category.getName(LocalAppLanguage.current),
                    onClick = { expanded = true },
                    label = str(AdminStringKeys.TseFindingsCategory),
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TseExplanationCategory.entries.forEach { entry ->
                        PosDropdownItem(
                            text = entry.getName(LocalAppLanguage.current),
                            selected = entry == category,
                            onClick = {
                                category = entry
                                expanded = false
                            },
                        )
                    }
                }
            }

            PosTextField(
                value = text,
                onValueChange = { text = it },
                label = str(AdminStringKeys.TseFindingsText),
                modifier = Modifier.fillMaxWidth(),
                isError = explainError != null,
                errorMessage = explainError?.let { key -> str(key) },
            )
        }
    }
}
