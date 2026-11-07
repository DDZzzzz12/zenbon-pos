package org.example.project.ui.components.filter

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing

/** Width of the panel as a right-side sheet (design token `filter-panel-width`). */
private val SHEET_WIDTH = 360.dp

/**
 * Every filter that does not fit [PosFilterBar]'s row (Zenbon "FilterPanel" pattern).
 *
 * **Phone width:** full screen, and it holds *everything* the bar has no room for — the period,
 * quick filters and the advanced fields. Its one primary button names the outcome:
 * "128 Ergebnisse anzeigen" from [resultCount] (keep it live, debounced), or "Anwenden" when null.
 * **Wider windows:** a [SHEET_WIDTH] sheet on the right over the list, which stays visible; it holds
 * only the advanced fields (period and quick filters stay in the bar), with Abbrechen · Anwenden.
 *
 * The fields are a draft owned by the caller: nothing applies until [onApply]; [onDismiss] (close,
 * Abbrechen, tap outside) must drop the draft. [onReset] clears the panel's fields; null (a panel whose fields cannot be cleared, like a mandatory
 * period) hides the reset button.
 *
 * The body scrolls itself — do not put another `verticalScroll` in [content] (the same infinite-height
 * crash `PosDialog` documents on wasm).
 */
@Composable
fun PosFilterPanel(
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReset: (() -> Unit)?,
    resultCount: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalPosLayout.current.isCompact
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (compact) {
            PanelSurface(Modifier.fillMaxSize(), compact = true, onDismiss, onApply, onReset, resultCount, content)
        } else {
            // A full-size box so the sheet can sit at the end; tapping the uncovered part dismisses.
            // The Surface consumes its own taps, so they never reach this handler.
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
                contentAlignment = Alignment.CenterEnd,
            ) {
                PanelSurface(
                    Modifier.width(SHEET_WIDTH).fillMaxHeight(),
                    compact = false,
                    onDismiss,
                    onApply,
                    onReset,
                    resultCount,
                    content,
                )
            }
        }
    }
}

@Suppress("LongParameterList") // private plumbing of the public function's own parameters
@Composable
private fun PanelSurface(
    modifier: Modifier,
    compact: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReset: (() -> Unit)?,
    resultCount: Int?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(onDismiss, onReset)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(PosSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
                content = content,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PanelFooter(compact, onDismiss, onApply, resultCount)
        }
    }
}

@Composable
private fun PanelHeader(
    onDismiss: () -> Unit,
    onReset: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = PosSpacing.xl, end = PosSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = str(PosPatternKeys.filter),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (onReset != null) {
            PosTextButton(text = str(PosPatternKeys.filterReset), onClick = onReset, height = 40.dp)
        }
        PosIconButton(icon = Icons.Filled.Close, contentDescription = str(CommonKeys.close), onClick = onDismiss)
    }
}

@Composable
private fun PanelFooter(
    compact: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    resultCount: Int?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = PosSpacing.xl, end = PosSpacing.xl, top = PosSpacing.md, bottom = PosSpacing.xl),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
    ) {
        val applyLabel =
            if (compact && resultCount != null) {
                str(PosPatternKeys.filterShowResults, resultCount)
            } else {
                str(PosPatternKeys.filterApply)
            }
        if (!compact) {
            PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        }
        PosButton(text = applyLabel, onClick = onApply, modifier = Modifier.weight(1f))
    }
}
