package org.example.project.admin.features.product.presentation.components

import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.product.presentation.ProductMeta
import org.example.project.core.presentation.strings.str

/** "Pfand · 2 Auswahlgruppen", or null when [meta] has nothing to say. */
@Composable
fun productMetaText(meta: ProductMeta): String? {
    if (meta.isEmpty) return null
    val parts = mutableListOf<String>()
    if (meta.hasPfand) parts += str(AdminStringKeys.ProductMetaPfand)
    if (meta.groupCount == 1) {
        parts += str(AdminStringKeys.ProductMetaGroupsOne, meta.groupCount)
    } else if (meta.groupCount > 1) {
        parts += str(AdminStringKeys.ProductMetaGroups, meta.groupCount)
    }
    return parts.joinToString(" · ")
}
