package org.example.project.features.salesinvoice

import org.example.project.core.domain.dto.salesinvoice.ArNumberingConfigDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.salesinvoice.domain.service.ArNumberingPolicy
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArNumberingPolicyTest {
    @Test
    fun `a distinct prefix is accepted unchanged`() {
        val config = ArNumberingConfigDto(prefix = "RG", startValue = 1L, padding = 5)

        assertEquals("RG", ArNumberingPolicy.validated(config).prefix)
        Unit
    }

    @Test
    fun `the bill prefix is refused, in any case`() {
        for (prefix in listOf("RE", "re", "Re")) {
            val config = ArNumberingConfigDto(prefix = prefix, startValue = 1L, padding = 5)
            val error =
                assertFailsWith<PosException> { ArNumberingPolicy.validated(config) }
            assertEquals(PosErrorCode.SALES_INVOICE_PREFIX_COLLISION, error.code)
        }
        Unit
    }

    @Test
    fun `a blank prefix is refused`() {
        val error =
            assertFailsWith<PosException> {
                ArNumberingPolicy.validated(ArNumberingConfigDto(prefix = "  ", startValue = 1L, padding = 5))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_PREFIX_COLLISION, error.code)
        Unit
    }

    @Test
    fun `a start value below one is refused`() {
        val error =
            assertFailsWith<PosException> {
                ArNumberingPolicy.validated(ArNumberingConfigDto(prefix = "RG", startValue = 0L, padding = 5))
            }
        assertEquals(PosErrorCode.SALES_INVOICE_PREFIX_COLLISION, error.code)
        Unit
    }
}
