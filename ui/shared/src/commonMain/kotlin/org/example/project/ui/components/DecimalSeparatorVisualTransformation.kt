package org.example.project.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shows a numeric field's `.` as [separator] (the German `,`) without touching the value.
 *
 * The field's value stays the canonical dot decimal the wire carries; only what the operator sees
 * changes (admin21 NOTE-e21: editors redisplayed "12.90" and "1.5000"). Both separators are still
 * accepted on input — [PosTextField]'s blur normalisation folds a typed `,` to `.`. The swap is one
 * character for one character, so the offset mapping is the identity.
 */
class DecimalSeparatorVisualTransformation(
    private val separator: Char,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(AnnotatedString(text.text.replace('.', separator)), OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean = other is DecimalSeparatorVisualTransformation && other.separator == separator

    override fun hashCode(): Int = separator.hashCode()
}
