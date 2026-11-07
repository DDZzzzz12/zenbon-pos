package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSearchBar

/** The Large/Medium search field: a placeholder, not a labelled field, so it lines up with the buttons beside it. */
@Composable
fun ProductSearchField(
    search: String,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosSearchBar(
        query = search,
        onQueryChange = onSearch,
        placeholder = str(AdminStringKeys.ProductsSearch),
        modifier = modifier.widthIn(min = 220.dp),
    )
}
