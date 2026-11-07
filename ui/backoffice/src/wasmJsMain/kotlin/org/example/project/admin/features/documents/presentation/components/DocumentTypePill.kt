package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.theme.PosSpacing

/**
 * The human label for a document type string. Server-configured additional types are not enum
 * entries, so an unrecognised value falls back to itself rather than disappearing.
 */
internal fun documentTypeLabel(
    fileType: String,
    language: Language,
): String = DocumentType.fromStringOrNull(fileType)?.getName(language) ?: fileType

/**
 * A small rounded label for a document's [fileType]. Resolves the localized display name for the
 * five known [DocumentType] entries; falls back to the raw server string for a server-configured
 * additional type that isn't one of those enum entries, so an unrecognized type still renders
 * something readable instead of a blank pill.
 */
@Composable
fun DocumentTypePill(
    fileType: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val label = documentTypeLabel(fileType, language)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PosSpacing.md, vertical = 4.dp),
        )
    }
}
