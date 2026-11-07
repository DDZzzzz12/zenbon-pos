package org.example.project.ui.components

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/** A numeric field shows the locale separator while its value stays canonical (QA admin21 NOTE-e21). */
class DecimalSeparatorVisualTransformationTest {
    private val german = DecimalSeparatorVisualTransformation(',')

    @Test
    fun theDotIsShownAsTheLocaleSeparator() {
        val shown = german.filter(AnnotatedString("12.90"))
        assertEquals("12,90", shown.text.text)
    }

    @Test
    fun aTypedCommaStaysAComma() {
        assertEquals("1,5", german.filter(AnnotatedString("1,5")).text.text)
    }

    @Test
    fun offsetsMapOneToOne() {
        val shown = german.filter(AnnotatedString("1234.56"))
        (0..7).forEach { assertEquals(it, shown.offsetMapping.originalToTransformed(it)) }
        (0..7).forEach { assertEquals(it, shown.offsetMapping.transformedToOriginal(it)) }
    }
}
