package org.example.project.ui.components.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.product.VisibilityScope
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.CategoriesTabKeys
import org.example.project.core.presentation.strings.keys.ProductsTabKeys
import org.example.project.core.presentation.strings.keys.VisibilityKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.PosFormFocus
import org.example.project.ui.components.form.requiredLabel

/**
 * Reusable category create/edit form fields, shared between composeApp and the wasm adminApp.
 * The bulk choice-group assignment section from composeApp's dialog is intentionally out of
 * scope here.
 */
@Composable
fun CategoryEditForm(
    state: CategoryFormState,
    onState: (CategoryFormState) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    /** Validation messages shown at the matching field (already localized). */
    errors: Map<CategoryFormField, String> = emptyMap(),
    /** Registers each validated field as a scroll-to-error target; null = no scrolling. */
    focus: PosFormFocus<CategoryFormField>? = null,
    /** Visibility as a 2x2 radio grid (web admin) instead of filter chips (composeApp). */
    visibilityAsRadios: Boolean = false,
) {
    fun anchor(field: CategoryFormField): Modifier = if (focus == null) Modifier else with(focus) { Modifier.formAnchor(field) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosTextField(
            value = state.name,
            onValueChange = { onState(state.copy(name = it)) },
            label = requiredLabel(str(CategoriesTabKeys.name)),
            modifier = anchor(CategoryFormField.NAME),
            isError = CategoryFormField.NAME in errors,
            supportingText = errors[CategoryFormField.NAME]?.let { msg -> { Text(msg) } },
        )
        PosTextField(
            value = state.enName,
            onValueChange = { onState(state.copy(enName = it)) },
            label = str(ProductsTabKeys.englishName),
        )
        PosTextField(
            value = state.chName,
            onValueChange = { onState(state.copy(chName = it)) },
            label = str(ProductsTabKeys.chineseName),
        )
        PosTextField(
            value = state.description,
            onValueChange = { onState(state.copy(description = it)) },
            label = str(CategoriesTabKeys.description),
            singleLine = false,
            maxLines = 3,
        )
        PosTextField(
            value = state.sortPosition,
            onValueChange = { onState(state.copy(sortPosition = it)) },
            label = str(CategoriesTabKeys.sortPosition),
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        )

        // Active toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(CategoriesTabKeys.active))
            Switch(checked = state.isActive, onCheckedChange = { onState(state.copy(isActive = it)) })
        }

        // Visibility chips
        Text(
            text = str(VisibilityKeys.visibility),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (visibilityAsRadios) {
            VisibilityRadioGrid(selected = state.visibility, onSelect = { onState(state.copy(visibility = it)) })
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisibilityScope.entries.forEach { scope ->
                    FilterChip(
                        selected = state.visibility == scope.name,
                        onClick = { onState(state.copy(visibility = scope.name)) },
                        label = { Text(visibilityLabel(scope), style = MaterialTheme.typography.labelSmall) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                }
            }
        }
    }
}

/** The four visibility scopes as a 2x2 grid of bordered radio cells. */
@Composable
private fun VisibilityRadioGrid(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VisibilityScope.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { scope ->
                    val isSelected = selected == scope.name
                    Surface(
                        onClick = { onSelect(scope.name) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(visibilityLabel(scope), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun visibilityLabel(scope: VisibilityScope): String =
    when (scope) {
        VisibilityScope.EVERYWHERE -> str(VisibilityKeys.everywhere)
        VisibilityScope.POS_ONLY -> str(VisibilityKeys.posOnly)
        VisibilityScope.WEBSITE_ONLY -> str(VisibilityKeys.websiteOnly)
        VisibilityScope.HIDDEN -> str(VisibilityKeys.hidden)
    }
