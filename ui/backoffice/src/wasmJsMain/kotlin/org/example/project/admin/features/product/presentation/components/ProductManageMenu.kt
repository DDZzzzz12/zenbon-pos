package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton

/**
 * The "Verwalten" menu of the Medium and Compact layouts: the extras, choice-group and promotion
 * managers and the category list. A labelled button on Medium, a ⋮ overflow icon ([iconOnly]) on Compact
 * (in the phone top bar, see ProductCompactLayout).
 * The caller composes it only for `canManageProducts`.
 */
@Composable
fun ProductManageMenu(
    managers: ProductManagerActions,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val height = adminTouchSize(40.dp)
    val choose: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    // The Box is load-bearing: the dropdown anchors to its parent layout node.
    Box(modifier) {
        if (iconOnly) {
            PosIconButton(Icons.Default.MoreVert, str(AdminStringKeys.ProductsManage), { open = true }, size = 48.dp, iconSize = 24.dp)
        } else {
            PosSecondaryButton(
                text = str(AdminStringKeys.ProductsManage),
                icon = Icons.Default.Tune,
                onClick = { open = true },
                height = height,
            )
        }
        PosDropdown(expanded = open, onDismissRequest = { open = false }) {
            PosDropdownItem(text = str(AdminStringKeys.ProductsManageExtras), minHeight = height, onClick = choose(managers.onExtras))
            PosDropdownItem(text = str(AdminStringKeys.ProductsManageChoiceGroups), minHeight = height, onClick = choose(managers.onChoiceGroups))
            PosDropdownItem(text = str(AdminStringKeys.ProductsManagePromotions), minHeight = height, onClick = choose(managers.onPromotions))
            PosDropdownItem(text = str(AdminStringKeys.ProductsManageCategories), minHeight = height, onClick = choose(managers.onCategories))
        }
    }
}
