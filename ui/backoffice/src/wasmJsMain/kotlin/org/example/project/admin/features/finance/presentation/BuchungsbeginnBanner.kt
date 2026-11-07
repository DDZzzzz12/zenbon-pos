package org.example.project.admin.features.finance.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.PosSpacing

/**
 * The Buchungsbeginn notice, shown on Rechnungen and on Zuordnung alike, drawn as the Belegfluss
 * design's thin line: calendar icon, "Buchungsbeginn 01.01.2026" and a green *Ändern* link.
 *
 * It is raised by the server's `needsAcknowledgement`, which stays true on every read until a
 * manager confirms the date, so whichever of the two screens is opened first shows it and it
 * survives a reload. Dismissing it therefore has to reach the server — see
 * [BookkeepingStartController.acknowledge]. Because the notice would otherwise be permanent, the
 * line carries a second, quieter link (*Schließen*) while it is unacknowledged; [BuchungsbeginnLine]
 * is the same line without it.
 */
@Composable
fun BuchungsbeginnBanner(
    startDate: String,
    canChange: Boolean,
    onChange: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BuchungsbeginnRow(startDate = startDate, canChange = canChange, onChange = onChange, onDismiss = onDismiss, modifier = modifier)
}

/**
 * The Buchungsbeginn once acknowledged — shown on the same screens, so the date stays visible and
 * changeable. One quiet line: the decision is made, it only has to stay reachable.
 */
@Composable
fun BuchungsbeginnLine(
    startDate: String,
    canChange: Boolean,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BuchungsbeginnRow(startDate = startDate, canChange = canChange, onChange = onChange, onDismiss = null, modifier = modifier)
}

/**
 * Both links write (`ACCOUNTING_WRITE`): a read-only role sees the date without them. The links wrap
 * under the text on a narrow width instead of squeezing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuchungsbeginnRow(
    startDate: String,
    canChange: Boolean,
    onChange: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Event,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = str(AdminStringKeys.BookkeepingStartCurrent, startDate.asDisplayDay(LocalAppLanguage.current)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canChange) {
            PosTextButton(
                text = str(AdminStringKeys.BookkeepingStartChange),
                onClick = onChange,
                height = adminTouchSize(32.dp),
                contentPadding = PaddingValues(horizontal = PosSpacing.md),
            )
            if (onDismiss != null) {
                PosTextButton(
                    text = str(CommonKeys.close),
                    onClick = onDismiss,
                    height = adminTouchSize(32.dp),
                    contentPadding = PaddingValues(horizontal = PosSpacing.md),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

/**
 * Edits the cut-off with the one date field (UI-P1-08): typed as TT.MM.JJJJ or picked, ISO on the
 * wire.
 *
 * *Speichern* is never greyed out for a missing or malformed date (UI-E2-06): pressing it marks the
 * field and focuses it. The server answers 400 for a non-ISO date as well (rule 8); the controller's
 * `save` refuses one before the request too.
 *
 * No `verticalScroll` here: `PosDialog` already scrolls its body, and nesting a second scroll
 * crashes the wasm measure pass with an infinite-height constraint.
 */
@Composable
fun BuchungsbeginnDialog(
    currentValue: String,
    isSaving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember(currentValue) { mutableStateOf(currentValue) }
    var attempt by remember { mutableStateOf(0) }
    val error = if (attempt > 0) buchungsbeginnError(typed) else null
    val focus = rememberPosFormFocus<String>()
    LaunchedEffect(attempt) {
        if (attempt > 0) focus.revealFirst(listOf(DATE_FIELD), mapOfNotNull(DATE_FIELD, buchungsbeginnError(typed)))
    }
    val hint = str(CommonKeys.dateFormatHint)

    val close = rememberGuardedDismiss(dirty = isBuchungsbeginnDirty(typed, currentValue), onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.BookkeepingStartTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.save),
                onClick = { if (buchungsbeginnError(typed) == null) onSave(typed) else attempt++ },
                isLoading = isSaving,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = str(AdminStringKeys.BookkeepingStartHint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosDateField(
                value = typed,
                onValueChange = { typed = it },
                label = requiredLabel(str(AdminStringKeys.BookkeepingStartTitle)),
                isError = error != null,
                supportingText = error?.let { key -> str(key, hint) },
                modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(DATE_FIELD) },
            )
        }
    }
}

/** Focus key of the dialog's one field. */
private const val DATE_FIELD = "date"

/**
 * Why [typed] cannot be saved as the Buchungsbeginn, or null: required, and an ISO date (the date
 * field hands over ISO for anything it could read, the typed text otherwise). The message takes the
 * typing pattern as `{0}` where it needs one.
 */
internal fun buchungsbeginnError(typed: String): StringKey? =
    when {
        typed.isBlank() -> CommonKeys.fieldRequired
        isoDateOrNull(typed) == null -> CommonKeys.dateInvalid
        else -> null
    }

private fun <K, V : Any> mapOfNotNull(
    key: K,
    value: V?,
): Map<K, V> = if (value == null) emptyMap() else mapOf(key to value)

/** The typed date differs from the one the dialog opened with; edit-then-undo is clean. */
internal fun isBuchungsbeginnDirty(
    typed: String,
    currentValue: String,
): Boolean = typed.trim() != currentValue
