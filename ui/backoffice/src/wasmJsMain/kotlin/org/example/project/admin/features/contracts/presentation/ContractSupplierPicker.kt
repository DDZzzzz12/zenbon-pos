package org.example.project.admin.features.contracts.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField

/**
 * The contract's supplier, picked by name. The form keeps only the id; the operator no longer has to
 * know it. A stored id missing from [suppliers] (not loaded yet, or beyond the first page) shows the
 * server-resolved name, or `#id`, rather than blank, so the link is never silently hidden.
 *
 * Limitation: [suppliers] is capped at the API's default list limit, so beyond that page a supplier
 * can only be shown through the fallback (server `supplierName` / `#id`), not picked by name.
 */
@Composable
internal fun ContractSupplierPicker(
    selectedId: Long?,
    suppliers: List<SupplierDto.ResponseDto>,
    loadedName: String?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val noneLabel = str(AdminStringKeys.ContractSupplierNone)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fieldWidth = maxWidth
        Box(modifier = Modifier.fillMaxWidth()) {
            PosPickerField(
                value = supplierDisplayName(selectedId, suppliers, noneLabel, loadedName),
                onClick = { open = true },
                label = str(AdminStringKeys.ContractFieldSupplier),
                modifier = Modifier.fillMaxWidth(),
            )
            PosDropdown(
                expanded = open,
                onDismissRequest = { open = false },
                minWidth = fieldWidth,
                maxWidth = fieldWidth,
            ) {
                Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Column {
                        PosDropdownItem(
                            text = noneLabel,
                            selected = selectedId == null,
                            minHeight = 48.dp,
                            onClick = {
                                onSelect(null)
                                open = false
                            },
                        )
                        suppliers.forEach { supplier ->
                            PosDropdownItem(
                                text = supplier.name,
                                selected = supplier.id == selectedId,
                                minHeight = 48.dp,
                                onClick = {
                                    onSelect(supplier.id)
                                    open = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The name for [selectedId], [loadedName] or `#id` when it is not in [suppliers], [noneLabel] when nothing is chosen. */
internal fun supplierDisplayName(
    selectedId: Long?,
    suppliers: List<SupplierDto.ResponseDto>,
    noneLabel: String,
    loadedName: String? = null,
): String =
    when (selectedId) {
        null -> noneLabel
        else -> suppliers.firstOrNull { it.id == selectedId }?.name ?: loadedName?.takeIf { it.isNotBlank() } ?: "#$selectedId"
    }
