package org.example.project.ui.components.pdf

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.PdfViewerKeys
import org.example.project.core.presentation.strings.str

/**
 * "[n] / total": the current page, editable. Confirming jumps to the typed page clamped to the document
 * ([pdfPageJumpTarget]) and the field then shows that page, even when the list cannot scroll to it; leaving the field
 * without confirming — including a press elsewhere in the viewer, which clears focus — puts the current page back
 * ([PdfPageJumpEdit]). Either way an out-of-range or unreadable number
 * never stays on screen. Shown only for an open document.
 */
@Composable
internal fun PdfPageJumpField(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
) {
    var edit by remember(currentPage) { mutableStateOf(PdfPageJumpEdit.showing(currentPage)) }
    val shownPage by rememberUpdatedState(currentPage)
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val label = str(PdfViewerKeys.pageOf, currentPage, pageCount)
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = edit.text,
            onValueChange = { typed -> edit = edit.typed(typed) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions =
                KeyboardActions(onGo = {
                    val target = edit.target(shownPage, pageCount)
                    edit = PdfPageJumpEdit.showing(target)
                    onJump(target)
                    // Fires onFocusChanged at once; the edit is no longer pending, so the target stays.
                    focusManager.clearFocus()
                }),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.primary),
            modifier =
                Modifier
                    // Outermost: sees the press after the text field has, and keeps the viewer from clearing focus.
                    .pdfClaimsPresses()
                    .onPreviewKeyEvent { event ->
                        // Esc cancels this edit only, on KeyDown (see PdfPageJumpEscape): the browser may never
                        // deliver the KeyUp here. Both phases are consumed so app-level back handling never fires.
                        val action =
                            PdfPageJumpEscape.of(
                                isEscape = event.key == Key.Escape,
                                isKeyDown = event.type == KeyEventType.KeyDown,
                            )
                        if (action == PdfPageJumpEscape.Cancel) {
                            edit = edit.cancel(shownPage)
                            focusManager.clearFocus()
                        }
                        action.consumes
                    }.width(48.dp)
                    .onFocusChanged { focus ->
                        // Losing focus without confirming: the typed number is dropped, not jumped to.
                        if (focused && !focus.isFocused) edit = edit.focusLost(shownPage)
                        focused = focus.isFocused
                    }.border(1.dp, colors.outline, RoundedCornerShape(4.dp))
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .semantics { contentDescription = label },
        )
        Text(
            text = str(PdfViewerKeys.ofTotal, pageCount),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}
