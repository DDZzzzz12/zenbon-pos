package org.example.project.admin.features.contracts

import org.example.project.admin.features.contracts.presentation.asEditableAmount
import org.example.project.core.utils.toCanonicalMoneyOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class EditableAmountTest {
    @Test
    fun serverPaddingBecomesScaleTwoWithComma() {
        assertEquals("149,00", "149.0000".asEditableAmount())
        assertEquals("5,00", "5".asEditableAmount())
        assertEquals("16,895", "16.8950".asEditableAmount())
    }

    @Test
    fun missingAmountIsBlank() {
        assertEquals("", null.asEditableAmount())
    }

    @Test
    fun theCommaFormReadsBackExactly() {
        assertEquals("149.00", "149.0000".asEditableAmount().toCanonicalMoneyOrNull())
    }
}
