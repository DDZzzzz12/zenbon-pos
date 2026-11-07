package org.example.project.admin.features.permissions.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.localizedCapabilityArea
import org.example.project.core.utils.enumStrings.localizedCapabilityName
import org.example.project.ui.theme.PosSpacing

/**
 * The capability checklist, grouped by the first segment of each name.
 *
 * The grouping is derived from the names rather than curated, so a capability added on the server
 * lands in a sensible section without a client change. That matters here more than usual: the list
 * comes from the server's catalog, not the client's enum, precisely so a newer server's
 * capabilities are offered rather than silently dropped.
 *
 * A **reserved** capability renders disabled with a note instead of being hidden. Hiding it would
 * make the ceiling invisible, and an operator would keep looking for a way to grant "close the day"
 * — which no group edit can ever do.
 */
@Composable
fun CapabilityPicker(
    byArea: Map<String, List<PermissionGroupDto.CapabilityInfoDto>>,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        byArea.forEach { (area, capabilities) ->
            CapabilityArea(
                area = area,
                capabilities = capabilities,
                selected = selected,
                enabled = enabled,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun CapabilityArea(
    area: String,
    capabilities: List<PermissionGroupDto.CapabilityInfoDto>,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    val held = capabilities.count { it.name in selected }
    var expanded by remember(area) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // An icon, not "▾"/"▸": neither triangle is in adminApp's font subset, so the text
            // glyphs drew as nothing and the header lost its expand cue.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(PosSpacing.sm))
                Text(
                    text = localizedCapabilityArea(area, LocalAppLanguage.current),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // The count is on the collapsed header on purpose: an operator scanning a closed list
            // still sees where this group's reach actually is.
            Text(
                text = "$held / ${capabilities.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            capabilities.forEach { capability ->
                CapabilityRow(
                    capability = capability,
                    checked = capability.name in selected,
                    enabled = enabled && capability.assignable,
                    onToggle = { onToggle(capability.name) },
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    capability: PermissionGroupDto.CapabilityInfoDto,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            // Localized name, the code underneath (admin19 NOTE-c): the code is what an audit row,
            // a 403 and the server's own messages name, so the operator can still match them up.
            Text(
                text = localizedCapabilityName(capability.name, LocalAppLanguage.current),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = capability.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!capability.assignable) {
                Text(
                    text = str(AdminStringKeys.PermissionCapabilityReserved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
