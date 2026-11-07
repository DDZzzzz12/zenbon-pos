package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.rotation.RotationNextStore
import org.example.project.ui.components.PosStatusChip
import org.koin.compose.koinInject

/**
 * "Next: <name>" — the employee the round-robin rotation deals the next free table to.
 *
 * Shown by BOTH dashboard top bars (grid/phone and floor plan), so every till carries it. Visibility
 * follows the store alone: the server sends `next = null` when round-robin is off or the pool is empty,
 * so a till whose local copy of the order rules is stale still shows the right thing.
 */
@Composable
fun RotationNextChip(modifier: Modifier = Modifier) {
    val next by koinInject<RotationNextStore>().next.collectAsState()
    val name = rotationNextChipName(next) ?: return
    // At a large font the one-line chip would cut the name, so it wraps instead.
    val enlarged = LocalDensity.current.fontScale > 1.15f
    val description = str(RulesKeys.rotationNextContentDescription, name)
    PosStatusChip(
        text = str(RulesKeys.rotationNextLabel, name),
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
        singleLine = !enlarged,
        modifier =
            modifier
                .widthIn(max = 220.dp)
                .semantics { contentDescription = description },
    )
}

/** The name the chip shows for [next], or null when the chip is hidden (nobody is next). */
fun rotationNextChipName(next: RotationDto.EmployeeRefDto?): String? = next?.let { it.displayName ?: it.employeeNumber }
