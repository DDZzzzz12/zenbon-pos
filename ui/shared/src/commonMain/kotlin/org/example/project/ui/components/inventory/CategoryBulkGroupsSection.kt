package org.example.project.ui.components.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.presentation.strings.keys.ProductManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.form.PosFormSectionLabel

/**
 * Bulk-assign choice groups across every product in a category. Extracted from composeApp's
 * category dialog so the same section is reused by the desktop dialog AND the web admin drawer.
 * The Switch is "on" when the group is attached to ALL products in the category (coverage == total).
 * A group attached to some but not all products shows two explicit buttons instead of a switch,
 * placed below the group name so the name never has to share its line with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBulkGroupsSection(
    categoryId: Int,
    choiceGroups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    groupCoverage: Map<Int, Int>,
    totalProductsInCategory: Int,
    onBulkAssignGroup: (Int, Int) -> Unit,
    onBulkRemoveGroup: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Web admin layout: a small-caps heading and, per group, the coverage plus text buttons "Allen zuweisen" / "Entfernen". */
    rowActions: Boolean = false,
) {
    if (choiceGroups.isEmpty()) return
    if (rowActions) {
        RowActionsBody(categoryId, choiceGroups, groupCoverage, totalProductsInCategory, onBulkAssignGroup, onBulkRemoveGroup, modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            str(ProductManagementKeys.bulkAssignChoiceGroups),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        choiceGroups.forEach { group ->
            val coverage = groupCoverage[group.id] ?: 0
            val isFullyAssigned = totalProductsInCategory > 0 && coverage == totalProductsInCategory
            if (isPartiallyAssigned(coverage, totalProductsInCategory)) {
                // FO-75: "2 / 7" is neither on nor off, so a switch would lie. Two explicit actions.
                // NEW-01: they sit BELOW the name, in a FlowRow, so the name keeps the full width (it
                // used to be squeezed beside both buttons, one syllable per line) and the buttons
                // stack when they do not fit side by side.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GroupLabel(group.name, coverage, totalProductsInCategory)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PosSecondaryButton(
                            text = str(ProductManagementKeys.assignToAllProducts),
                            onClick = { onBulkAssignGroup(categoryId, group.id) },
                        )
                        PosSecondaryButton(
                            text = str(ProductManagementKeys.removeFromAllProducts),
                            onClick = { onBulkRemoveGroup(categoryId, group.id) },
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupLabel(group.name, coverage, totalProductsInCategory, Modifier.weight(1f))
                    Switch(
                        checked = isFullyAssigned,
                        onCheckedChange = { checked ->
                            if (checked) onBulkAssignGroup(categoryId, group.id) else onBulkRemoveGroup(categoryId, group.id)
                        },
                        // 48dp touch target on every platform (the Switch itself is 32dp tall).
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/**
 * One row per group: name with "x von y Produkten", then two text buttons. Assign is disabled once
 * every product has the group (or the category is empty), remove while none has it.
 */
@Composable
private fun RowActionsBody(
    categoryId: Int,
    choiceGroups: List<ChoiceGroupDto.ChoiceGroupResponseDto>,
    groupCoverage: Map<Int, Int>,
    total: Int,
    onBulkAssignGroup: (Int, Int) -> Unit,
    onBulkRemoveGroup: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        PosFormSectionLabel(str(ProductManagementKeys.bulkAssignChoiceGroups))
        choiceGroups.forEach { group ->
            val coverage = groupCoverage[group.id] ?: 0
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GroupLabel(group.name, coverage, total, Modifier.weight(1f))
                PosTextButton(
                    text = str(ProductManagementKeys.assignToAllProducts),
                    onClick = { onBulkAssignGroup(categoryId, group.id) },
                    enabled = total > 0 && coverage < total,
                    height = 40.dp,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                )
                PosTextButton(
                    text = str(ProductManagementKeys.removeFromAllProducts),
                    onClick = { onBulkRemoveGroup(categoryId, group.id) },
                    enabled = coverage > 0,
                    height = 40.dp,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                )
            }
        }
    }
}

/** The group's name and "x / y Produkte" coverage line. */
@Composable
private fun GroupLabel(
    name: String,
    coverage: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(
            str(ProductManagementKeys.coverageProducts, coverage.toString(), total.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** True when a group is attached to some, but not all, of a category's [total] products. */
fun isPartiallyAssigned(
    coverage: Int,
    total: Int,
): Boolean = total > 0 && coverage > 0 && coverage < total
