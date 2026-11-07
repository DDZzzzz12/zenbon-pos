package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosIconButton

/**
 * Panel header with navigation controls.
 */
@Composable
fun PanelHeader(
    step: String,
    onClose: () -> Unit,
    onBack: () -> Unit,
    showBackButton: Boolean,
    currentLanguage: Language,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showBackButton) {
                PosIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = str(CustomerAddressKeys.back),
                    onClick = onBack,
                )
            }

            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
            )

            Text(
                text = step,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        PosIconButton(
            icon = Icons.Default.Close,
            contentDescription = getString(CommonKeys.close, currentLanguage),
            onClick = onClose,
        )
    }
}
