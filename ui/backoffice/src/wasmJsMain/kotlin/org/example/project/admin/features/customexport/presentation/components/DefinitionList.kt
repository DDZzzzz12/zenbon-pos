package org.example.project.admin.features.customexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.theme.PosSpacing

/**
 * The saved exports, as the left pane of the builder.
 *
 * This is the screen's **only** lazy list. The editor beside it lives in a plain vertical scroll and
 * everything inside that editor sizes to content, because a lazy list nested in a scrolling parent
 * is measured with an unbounded height and takes the wasm canvas down with it at runtime rather than
 * failing to compile.
 *
 * A broken definition is shown, not hidden: its badge is the only place an operator is told that a
 * field the export depends on has gone, and the fix is to open it — which is impossible if the row
 * is filtered away.
 */
@Composable
fun DefinitionList(
    definitions: List<CustomExportDto.DefinitionDto>,
    subjects: List<CustomExportDto.SubjectDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (definitions.isEmpty()) {
        Text(
            text = str(AdminStringKeys.CustomExportEmpty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 8.dp),
        )
        return
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
        items(definitions, key = { it.id }) { definition ->
            val subject = subjects.firstOrNull { it.key == definition.subjectKey }
            DefinitionRow(
                definition = definition,
                // Falls back to the raw key rather than blank: a definition whose subject the
                // catalogue no longer knows is exactly the one whose key the operator needs to read.
                subjectLabel = subject?.label ?: definition.subjectKey,
                selected = definition.id == selectedId,
                onClick = { onSelect(definition.id) },
            )
        }
    }
}

@Composable
private fun DefinitionRow(
    definition: CustomExportDto.DefinitionDto,
    subjectLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    PosCard(onClick = onClick, selected = selected) {
        body {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = definition.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (definition.isBroken) {
                    PosStatusChip(
                        text = str(AdminStringKeys.CustomExportBroken),
                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Text(
                text = subjectLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
