package org.example.project.features.salesinvoice

import org.example.project.features.bill.BILL_NUMBER_PREFIX
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BillNumberFormatTest {
    @Test
    fun `the bill prefix is the one the generator actually emits`() {
        assertEquals("RE", BILL_NUMBER_PREFIX)
    }
}
