package org.example.project.admin.core.presentation.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSegmentedButtonRow

/**
 * A document and its data, placed for the width the content has (see [pdfFormModeFor]).
 *
 * Wide: the PDF fills the free width, the form has [formWidth]. Narrower: a "Beleg | Daten" switch
 * and one pane at a time, each with the full height. The page itself does not scroll: each slot
 * scrolls internally (the PDF viewer its pages, the form its fields), so both stay usable at any height.
 *
 * Both slots stay composed while switched (the hidden one at zero size), so an unsaved form and a
 * loaded PDF survive a switch. Crossing between the two modes (a resize, a tablet rotation) moves the
 * slots rather than recreating them ([movableContentOf]): the PDF viewer keeps its open document, page,
 * zoom and scroll, and the form its local state. [pdf] and [form] receive the modifier that gives them
 * their size and must apply it to their root.
 */
@Composable
fun PdfFormPaneHost(
    pdf: @Composable (Modifier) -> Unit,
    form: @Composable (Modifier) -> Unit,
    initialPane: PdfFormPane,
    modifier: Modifier = Modifier,
    formWidth: Dp = 360.dp,
) {
    var pane by remember { mutableStateOf(initialPane) }
    val focusManager = LocalFocusManager.current
    val currentPdf by rememberUpdatedState(pdf)
    val currentForm by rememberUpdatedState(form)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Remembered inside BoxWithConstraints, whose content is its own composition: the slots move only
        // between the two branches below, both of which live in it.
        val pdfSlot = remember { movableContentOf { slotModifier: Modifier -> currentPdf(slotModifier) } }
        val formSlot = remember { movableContentOf { slotModifier: Modifier -> currentForm(slotModifier) } }
        when (pdfFormModeFor(maxWidth)) {
            PdfFormMode.SideBySide ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pdfSlot(Modifier.weight(1f).fillMaxHeight())
                    formSlot(Modifier.width(formWidth).fillMaxHeight())
                }
            PdfFormMode.Switched ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Min height on the row itself so the whole segment, not just its label, is a 48dp target.
                    PosSegmentedButtonRow(
                        options = PdfFormPane.entries,
                        selectedOption = pane,
                        onOptionSelected = {
                            // A field in the pane that is about to be hidden must not keep focus (and the
                            // phone keyboard) while nobody can see it.
                            focusManager.clearFocus()
                            pane = it
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { option ->
                        str(if (option == PdfFormPane.PDF) AdminStringKeys.PhonePanePdf else AdminStringKeys.PhonePaneData)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        pdfSlot(if (pane == PdfFormPane.PDF) Modifier.fillMaxSize() else HiddenPane)
                        formSlot(if (pane == PdfFormPane.FORM) Modifier.fillMaxSize() else HiddenPane)
                    }
                }
        }
    }
}

/** Zero-sized, clipped and not focusable: Tab must not enter an invisible field. */
private val HiddenPane: Modifier = Modifier.size(0.dp).clipToBounds().focusProperties { canFocus = false }
