package org.example.project.ui.components.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing

/** Content width from which a form section lays related fields side by side (`form-two-column-min`). */
private val TWO_COLUMN_MIN_WIDTH = 600.dp

/** Vertical gap between fields (design token `card-padding`). */
private val FIELD_GAP = PosSpacing.lg

/** Whether the enclosing [PosFormSection] is wide enough for two columns. */
private val LocalPosFormTwoColumns = staticCompositionLocalOf { false }

/**
 * A titled group of fields (Zenbon "FormSection" pattern): "Grunddaten", "Preis & Steuer", …
 *
 * Measures its own **content width** — not the window — so the same form reads correctly in a 420dp
 * drawer and on a full page: below 600dp every field is full width; from 600dp, fields placed in a
 * [PosFormRow] sit side by side. Fields placed directly in [content] always span the full width.
 * Stack sections in a Column spaced `PosSpacing.xxl`.
 */
@Composable
fun PosFormSection(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalPosFormTwoColumns provides (maxWidth >= TWO_COLUMN_MIN_WIDTH)) {
            Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.xs)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (hint != null) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                content()
            }
        }
    }
}

/**
 * Two related fields (PLZ + Ort, Netto + MwSt., Von + Bis): side by side in a wide [PosFormSection],
 * stacked in a narrow one. Each slot receives the modifier that sizes it — apply it to the field.
 */
@Composable
fun PosFormRow(
    start: @Composable (Modifier) -> Unit,
    end: @Composable (Modifier) -> Unit,
) {
    if (LocalPosFormTwoColumns.current) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.xl),
            verticalAlignment = Alignment.Top,
        ) {
            start(Modifier.weight(1f))
            end(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
            start(Modifier.fillMaxWidth())
            end(Modifier.fillMaxWidth())
        }
    }
}

/** "Pflichtfelder sind mit * markiert." — once per form, as the first section's hint or above it. */
@Composable
fun posFormRequiredNote(): String = str(PosPatternKeys.formRequiredNote)
