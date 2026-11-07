package org.example.project.admin.features.documents.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.components.AdminListEmptyState
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.presentation.strings.str

/** The left pane for a Beleg whose file type has no in-page preview (XML, MT940 text, ...). */
@Composable
fun DocumentNoPreviewPane(modifier: Modifier = Modifier) {
    AdminListEmptyState(
        message = str(DocumentManagementKeys.noPreviewAvailable),
        modifier = modifier,
    )
}
