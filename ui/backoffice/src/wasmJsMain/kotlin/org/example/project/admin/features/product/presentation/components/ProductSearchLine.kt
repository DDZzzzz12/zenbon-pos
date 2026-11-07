package org.example.project.admin.features.product.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductState
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.categoryDisplayName
import org.example.project.ui.components.filter.filterResultLine
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/** Large layout: the search field with the result line beside it ("Bier · 7 von 53 Produkten"). */
@Composable
fun ProductSearchLine(
    s: ProductState.Data,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = LocalPosLayout.current.screenPadding, vertical = PosSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductSearchField(s.search, onSearch, Modifier.weight(1f))
        Text(productResultLine(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun productResultLine(s: ProductState.Data): String {
    val shown = s.visibleProducts.size
    val total = s.allProducts.size
    val category = s.selectedCategoryId?.let { id -> s.categories.firstOrNull { it.id == id }?.name }
    return when {
        category != null -> str(AdminStringKeys.ProductsResultOfInCategory, categoryDisplayName(category, LocalAppLanguage.current), shown, total)
        shown != total -> str(AdminStringKeys.ProductsResultOf, shown, total)
        else -> filterResultLine(total, AdminStringKeys.ProductsResultCountOne, AdminStringKeys.ProductsResultCount)
    }
}
