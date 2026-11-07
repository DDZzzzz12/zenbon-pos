package org.example.project.ui.components.beleg

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private const val BOLD_MARK = "**"

/**
 * Splits [text] at `**` markers into (segment, bold) pairs; the segments between an odd and the next
 * marker are bold. An unmatched trailing marker leaves the rest plain, so a stray `**` in a
 * translation can never swallow the sentence. Empty segments are dropped.
 */
fun splitBoldMarkup(text: String): List<Pair<String, Boolean>> {
    val parts = text.split(BOLD_MARK)
    // An even number of markers leaves an odd number of parts; with an odd marker count the last
    // part has no closing marker and stays plain.
    val closed = (parts.size - 1) / 2 * 2
    return parts
        .mapIndexed { index, part -> part to (index % 2 == 1 && index <= closed) }
        .filter { it.first.isNotEmpty() }
}

/** [text] as an [AnnotatedString] with the `**…**` segments in bold. */
fun boldMarkupToAnnotated(text: String): AnnotatedString =
    buildAnnotatedString {
        splitBoldMarkup(text).forEach { (segment, bold) ->
            if (bold) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment) }
            } else {
                append(segment)
            }
        }
    }
