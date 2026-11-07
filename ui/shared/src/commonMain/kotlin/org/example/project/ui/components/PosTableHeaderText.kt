package org.example.project.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A table column heading that wraps only between words, never inside one.
 *
 * Compose breaks a word that is wider than its line, so a long German heading in a narrow weighted
 * column came out as "Rechnungsnumm / er" or "Gutscheincod / e" (QA admin19 NOTE-c). This measures
 * the heading's longest word and, if it does not fit the column, shrinks the font just enough that
 * it does (never below [minFontSize]); the heading may still wrap at spaces, up to two lines. Below
 * the floor it ellipsizes on one line rather than splitting a word.
 */
@Composable
fun PosTableHeaderText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    minFontSize: TextUnit = 10.sp,
) {
    val measurer = rememberTextMeasurer()
    val effective = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val available = constraints.maxWidth
        val longestWord = remember(text) { text.split(' ').maxByOrNull { it.length }.orEmpty() }
        val wordWidth =
            remember(longestWord, effective) {
                measurer.measure(longestWord, effective, maxLines = 1, softWrap = false).size.width
            }
        val fit = fitHeaderFontSize(effective.fontSize, wordWidth, available, minFontSize)
        Text(
            text = text,
            style = effective.copy(fontSize = fit.fontSize),
            color = color,
            textAlign = textAlign,
            maxLines = if (fit.fitsAWord) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
