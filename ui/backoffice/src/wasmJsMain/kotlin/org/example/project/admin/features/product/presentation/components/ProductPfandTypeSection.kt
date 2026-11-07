package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.example.project.admin.features.product.presentation.ProductEditor
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.form.PosFormSectionLabel

/**
 * The product form's **Pfand-Typ** selector: which deposit tier is charged with this product.
 *
 * Deposits are configured through Pfand types, never through raw `isDeposit` flags — a type owns its
 * own deposit and return articles, and a sellable product only points at the type. So this offers
 * "none" plus the active types, and nothing else.
 *
 * A Pfand type's own deposit/return article is shown read-only: the server refuses to move it to
 * another type (that would orphan it from the type that reprices it), and a control the server
 * refuses must not be offered. A link to a since-deactivated type is kept and shown by name, because
 * the list of active types no longer contains it.
 */
@Composable
fun ProductPfandTypeSection(
    editor: ProductEditor,
    pfandTypes: List<PfandTypeDto.PfandTypeResponseDto>,
    onSelect: (id: Long?, name: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentId = editor.pfandTypeId
    val linkedActive = pfandTypes.firstOrNull { it.id == currentId }
    val currentLabel =
        when {
            currentId == null -> str(AdminStringKeys.ProductPfandTypeNone)
            linkedActive != null -> linkedActive.name
            else -> str(AdminStringKeys.ProductPfandTypeInactive, editor.pfandTypeName ?: currentId.toString())
        }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PosFormSectionLabel(str(AdminStringKeys.ProductSectionPfand))
        Box(Modifier.padding(top = 8.dp)) {
            PosPickerField(
                value = currentLabel,
                onClick = { expanded = true },
                label = str(AdminStringKeys.ProductPfandType),
                enabled = !editor.isPfandArticle,
                modifier = Modifier.fillMaxWidth(),
            )
            PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                PosDropdownItem(
                    text = str(AdminStringKeys.ProductPfandTypeNone),
                    selected = currentId == null,
                    onClick = {
                        onSelect(null, null)
                        expanded = false
                    },
                )
                pfandTypes.forEach { type ->
                    PosDropdownItem(
                        text = type.name,
                        selected = type.id == currentId,
                        onClick = {
                            onSelect(type.id, type.name)
                            expanded = false
                        },
                    )
                }
            }
        }
        Text(
            text =
                if (editor.isPfandArticle) {
                    str(AdminStringKeys.ProductPfandTypeArticleHint, editor.pfandTypeName ?: "")
                } else {
                    str(AdminStringKeys.ProductPfandTypeHint)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
