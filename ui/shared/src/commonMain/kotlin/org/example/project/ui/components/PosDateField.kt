package org.example.project.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.datetime.LocalDate
import org.example.project.core.presentation.components.picker.calendarLocaleFor
import org.example.project.core.presentation.components.picker.pickerMillisToDate
import org.example.project.core.presentation.components.picker.toPickerMillis
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.dateInputText
import org.example.project.core.utils.dateWireValue
import org.example.project.core.utils.parseShortDate

/** Width of the calendar. A month grid needs about this much before the weeks wrap. */
private val CALENDAR_WIDTH = 360.dp

/** Height of the calendar with six week rows, for deciding whether it fits as a dropdown. */
private val CALENDAR_HEIGHT = 470.dp

/** Below this window width the calendar is a centred dialog instead of a dropdown (the phone top bar). */
private val CALENDAR_DROPDOWN_MIN_WINDOW = 600.dp

/**
 * The one date field of the back office (UI-P1-08 / SYS-19): typed *or* picked from a calendar.
 *
 * - **Display and typing in the UI language**: `01.03.2026` (German, English), `2026年03月01日`
 *   (Chinese), via the shared `formatShortDate`. The operator types in that shape; a pasted ISO date
 *   (`2026-03-01`) is accepted too (`parseShortDate`).
 * - **ISO on the wire**: [value] and [onValueChange] carry `yyyy-MM-dd` (or blank). Text that is not a
 *   date is passed through as typed ([dateWireValue]) so the form's own ISO check refuses it at Save,
 *   rather than the form silently keeping the last good date while the field shows something else.
 *   A caller storing `yyyy-MM-ddTHH:mm` passes the date half and puts the time back itself.
 * - **Invalid text shows a field error** once the field loses focus ("Datum als TT.MM.JJJJ eingeben"),
 *   unless the caller supplies its own [supportingText]/[isError] verdict, which wins.
 * - **The calendar speaks the UI language** (month names, weekday letters, header), whatever the
 *   browser locale ([calendarLocaleFor]).
 * - **The calendar opens on the value's month with the value selected**; a tap on any day — the
 *   selected one included — commits and closes. It drops below the field (flipping above when there is
 *   no room); on a phone-width window, or where it fits neither below nor above, it is a centred dialog
 *   instead, so it never covers the phone top bar or runs off the window. A dropdown rather than a modal on wide windows because the invoice-capture screen shows the
 *   scan beside the form: anchored under the field the calendar opens over the form column, not over
 *   the document being copied from.
 *
 * @param placeholder shown while the field is blank. Defaults to the localized typing pattern
 *   (`TT.MM.JJJJ`); a caller for whom blank *means* something (an open-ended export range) passes that
 *   meaning instead, because a pattern in an empty field reads as a date that will be applied.
 */
@Composable
fun PosDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: String? = null,
) {
    val language = LocalAppLanguage.current
    val currentValue by rememberUpdatedState(value)
    var text by remember { mutableStateOf(dateInputText(value, language)) }
    // The wire value this field last reported. A [value] different from it came from outside (a load, a
    // reset, a discard, the calendar) and replaces the text; the field's own echo does not.
    var reported by remember { mutableStateOf(value) }
    var shownIn by remember { mutableStateOf(language) }
    LaunchedEffect(value, language) {
        if (value != reported || language != shownIn) {
            text = dateInputText(value, language)
            reported = value
            shownIn = language
        }
    }

    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // Leaving the field tidies a valid date into the canonical shape ("1.3.2026" -> "01.03.2026");
    // while it has focus nothing is rewritten under the cursor.
    LaunchedEffect(focused) {
        if (!focused && parseShortDate(text) != null) text = dateInputText(dateWireValue(text), language)
    }
    val hint = str(CommonKeys.dateFormatHint)
    val unparseable = text.isNotBlank() && parseShortDate(text) == null
    val ownError = unparseable && !focused && supportingText == null
    val shownSupporting = supportingText ?: if (ownError) str(CommonKeys.dateInvalid, hint) else null

    fun commit(typed: String) {
        text = typed
        val wire = dateWireValue(typed)
        reported = wire
        if (wire != currentValue) onValueChange(wire)
    }

    var anchorTop by remember { mutableStateOf(0f) }
    var anchorBottom by remember { mutableStateOf(0f) }
    Box(
        modifier =
            modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                anchorTop = bounds.top
                anchorBottom = bounds.bottom
            },
    ) {
        PosTextField(
            value = text,
            onValueChange = ::commit,
            label = label,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            locked = readOnly,
            // Read-only (an issued invoice): no typing pattern and no calendar button, just the value,
            // like every other locked field; an empty one shows the em dash instead of "TT.MM.JJJJ".
            placeholder = if (readOnly) EMPTY_VALUE else placeholder ?: hint,
            isError = isError || ownError,
            supportingText = shownSupporting?.let { message -> { Text(message) } },
            trailingIcon =
                if (readOnly) {
                    null
                } else {
                    {
                        IconButton(onClick = { expanded = !expanded }, enabled = enabled) {
                            Icon(imageVector = Icons.Default.DateRange, contentDescription = str(CommonKeys.datePick))
                        }
                    }
                },
        )

        if (expanded && enabled && !readOnly) {
            DateFieldCalendar(
                selected = parseShortDate(text) ?: runCatching { LocalDate.parse(value.trim().substringBefore('T')) }.getOrNull(),
                onPicked = { date ->
                    expanded = false
                    commit(date.toString().let { iso -> dateInputText(iso, language) })
                },
                onDismiss = { expanded = false },
                anchorTop = anchorTop,
                anchorBottom = anchorBottom,
            )
        }
    }
}

/** Popup on a wide window where it fits, centred dialog on a phone-width window or where it does not; see [PosDateField]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFieldCalendar(
    selected: LocalDate?,
    onPicked: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    anchorTop: Float,
    anchorBottom: Float,
) {
    val openOn = selected?.toPickerMillis()
    // Month names, weekday letters and the month/year header in the UI language, not the browser's.
    val language = LocalAppLanguage.current
    val material =
        remember(language) {
            DatePickerState(locale = calendarLocaleFor(language), initialSelectedDateMillis = openOn, initialDisplayedMonthMillis = openOn)
        }
    val onPick by rememberUpdatedState(onPicked)
    val state = remember(material) { CommitOnPick(material) { millis -> onPick(pickerMillisToDate(millis)) } }

    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { window.width.toDp() }
    // A dropdown only where it fits whole below or above the field; otherwise (a short window, a field
    // in the middle of a dialog) the centred dialog, rather than a month grid running off the window.
    val calendarHeight = with(density) { CALENDAR_HEIGHT.toPx() }
    val fitsAsDropdown = anchorBottom + calendarHeight <= window.height || anchorTop - calendarHeight >= 0f
    if (windowWidth < CALENDAR_DROPDOWN_MIN_WINDOW || !fitsAsDropdown) {
        // Not the platform's default dialog width: it is narrower than a month grid, and the Sunday
        // column was cut off at 390dp. The surface takes the window width less a 16dp gutter instead.
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CalendarSurface(state, Modifier.padding(horizontal = 16.dp).widthIn(max = CALENDAR_WIDTH).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
                }
            }
        }
    } else {
        Popup(
            popupPositionProvider = BelowAnchor,
            onDismissRequest = onDismiss,
            // Focusable so Escape and an outside click close it; with no Cancel button those are the
            // only ways out.
            properties = PopupProperties(focusable = true),
        ) {
            CalendarSurface(state, Modifier.width(CALENDAR_WIDTH)) {}
        }
    }
}

/** The month grid on its own surface: a bare [DatePicker] draws on nothing and the form shows through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSurface(
    state: DatePickerState,
    modifier: Modifier,
    footer: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            DatePicker(
                state = state,
                title = null,
                // No headline: the value is legible in the field, and a pick closes the calendar.
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            footer()
        }
    }
}

/**
 * Material's state with one change: every day tap is reported, the already-selected day included.
 *
 * Material offers no per-day click callback, only the state's setter. Observing the value would miss a
 * tap on the day that is already selected (the value does not change), leaving the calendar open on the
 * one date guaranteed to be under the cursor. Intercepting the setter sees every tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class CommitOnPick(
    private val delegate: DatePickerState,
    private val onPick: (Long) -> Unit,
) : DatePickerState by delegate {
    override var selectedDateMillis: Long?
        get() = delegate.selectedDateMillis
        set(millis) {
            delegate.selectedDateMillis = millis
            if (millis != null) onPick(millis)
        }
}

/**
 * Drops the calendar directly beneath the field, left edges aligned, and flips it above when there is
 * no room below; when neither fits it sits as low as it can, so it never climbs over the window top.
 */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val height = popupContentSize.height
        val y =
            when {
                anchorBounds.bottom + height <= windowSize.height -> anchorBounds.bottom
                anchorBounds.top - height >= 0 -> anchorBounds.top - height
                else -> (windowSize.height - height).coerceAtLeast(0)
            }
        val x =
            anchorBounds.left
                .coerceAtMost(windowSize.width - popupContentSize.width)
                .coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
