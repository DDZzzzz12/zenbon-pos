package org.example.project.ui.components.beleg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.PosSpacing

/**
 * The "Verlauf" of a document: its steps in order, joined by a thin connector. A reached step is a
 * ticked box in the primary colour, the step waiting next an outlined primary box with a bold label,
 * a later one a muted empty box. State is the icon *and* the label weight, never colour alone.
 */
@Composable
fun PosFlowTimeline(
    steps: List<PosFlowStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (step.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint =
                            when {
                                step.done || step.current -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        modifier = Modifier.size(20.dp),
                    )
                    if (index < steps.lastIndex) {
                        Box(
                            Modifier
                                .padding(vertical = PosSpacing.xs)
                                .width(1.dp)
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Column(Modifier.padding(bottom = if (index < steps.lastIndex) PosSpacing.sm else 0.dp)) {
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (step.done || step.current) FontWeight.Bold else FontWeight.Normal,
                        color = if (step.done || step.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    step.detail?.let {
                        Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
