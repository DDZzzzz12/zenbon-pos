package org.example.project.admin.features.product.presentation.components

import androidx.compose.runtime.Composable
import org.example.project.core.domain.enums.product.VisibilityScope
import org.example.project.core.presentation.strings.keys.VisibilityKeys
import org.example.project.core.presentation.strings.str

/**
 * The visibility of a category when it is not the default: "Nur Kasse", "Nur Website", "Versteckt".
 * Null for "everywhere" (the default is not worth a line) and for a value this client does not know.
 */
@Composable
fun categoryVisibilityLabel(visibility: String): String? =
    when (VisibilityScope.entries.firstOrNull { it.name == visibility }) {
        VisibilityScope.POS_ONLY -> str(VisibilityKeys.posOnly)
        VisibilityScope.WEBSITE_ONLY -> str(VisibilityKeys.websiteOnly)
        VisibilityScope.HIDDEN -> str(VisibilityKeys.hidden)
        VisibilityScope.EVERYWHERE, null -> null
    }
