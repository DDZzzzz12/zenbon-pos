package org.example.project.features.dsfinvk

import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.utils.DsfinvkFieldLimits
import org.example.project.export.dsfinvk.model.DsfinvkIndexXml
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M1 (isolated audit 2026-07-22): four operator-supplied values reached the CSVs longer than the
 * `MaxLength` the export's own `index.xml` declares for their column. Measured on a real generated
 * package: `BEDIENER_NAME` 59/50 — which fires on EVERY Bonkopf row — `ARTIKELTEXT` 410/255,
 * `WARENGR` 80/50, `KUNDE_NAME` 56/50. Auditor software parses each CSV through those ordered
 * `VariableColumn` descriptors, so the package contradicts its own schema.
 *
 * Round-7 C7 fixed the shop Stammdaten by REJECTING at the write boundary rather than truncating
 * at export — a fiscal record must not be silently rewritten; a wrong-but-intact value stays
 * auditable, a shortened one does not. It deliberately left BEDIENER_NAME, KUNDE_NAME,
 * ZAHLART_NAME and WARENGR reported-but-unfixed. This closes them the same way.
 *
 * `ARTIKELTEXT` is the one exception and is handled differently on purpose — see
 * [artikeltext label is clamped because it cannot be rejected at any write boundary].
 */
@DisplayName("DSFinV-K Anhang E field lengths — operator-supplied values (M1)")
class DsfinvkOperatorFieldLengthTest {
    // ------------------------------------------------- the limits are the SPEC's, not the code's

    /**
     * Same guard as C7: derive the expected length from [DsfinvkIndexXml] — the verified
     * transcription of the reference `02_index.xml/index.xml` — so a hand-edited constant cannot
     * drift away from the schema the package is validated against.
     */
    @Test
    fun `the enforced limits equal the lengths declared in index-xml`() {
        fun declared(
            url: String,
            column: String,
        ) = (
            DsfinvkIndexXml
                .tables()
                .first { it.url == url }
                .columns
                .first { it.name == column }
                .type as DsfinvkIndexXml.Alpha
        ).maxLength

        assertEquals(declared("transactions.csv", "BEDIENER_NAME"), DsfinvkFieldLimits.BEDIENER_NAME)
        assertEquals(declared("transactions.csv", "KUNDE_NAME"), DsfinvkFieldLimits.KUNDE_NAME)
        assertEquals(declared("lines.csv", "WARENGR"), DsfinvkFieldLimits.WARENGR)
        assertEquals(declared("lines.csv", "ARTIKELTEXT"), DsfinvkFieldLimits.ARTIKELTEXT)
        assertEquals(declared("datapayment.csv", "ZAHLART_NAME"), DsfinvkFieldLimits.ZAHLART_NAME)
    }

    // --------------------------------------------------------------- the write boundary

    /**
     * The combined `first last` is what the exporter emits, so that is what must be measured —
     * validating the parts separately would let 25 + 25 characters through as a 51-character
     * BEDIENER_NAME.
     */
    @Test
    fun `an employee whose combined name exceeds BEDIENER_NAME is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmployeeInfoDto
                .CreateEmployeeInfoRequestDto(
                    employeeId = "e1",
                    firstName = "F".repeat(25),
                    lastName = "L".repeat(25),
                ).validate()
        }
        // 25 + 1 + 25 = 51 > 50; one character shorter must still pass.
        EmployeeInfoDto
            .CreateEmployeeInfoRequestDto(
                employeeId = "e1",
                firstName = "F".repeat(25),
                lastName = "L".repeat(24),
            ).validate()
    }

    @Test
    fun `an employee-info update with an over-long combined name is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EmployeeInfoDto
                .UpdateEmployeeInfoRequestDto(
                    firstName = "F".repeat(30),
                    lastName = "L".repeat(30),
                ).validate()
        }
        Unit
    }

    @Test
    fun `a customer whose combined name exceeds KUNDE_NAME is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CustomerDto
                .CreateCustomerRequestDto(
                    customerNumber = "C1",
                    firstName = "F".repeat(25),
                    lastName = "L".repeat(25),
                ).validate()
        }
        Unit
    }

    @Test
    fun `a product category name exceeding WARENGR is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProductCategoryDto.CreateProductCategoryRequestDto(name = "C".repeat(51), description = null).validate()
        }
        ProductCategoryDto.CreateProductCategoryRequestDto(name = "C".repeat(50), description = null).validate()
    }

    @Test
    fun `a payment type name exceeding ZAHLART_NAME is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentTypeDto.CreatePaymentTypeRequestDto(name = "P".repeat(61)).validate()
        }
        PaymentTypeDto.CreatePaymentTypeRequestDto(name = "P".repeat(60)).validate()
    }

    // --------------------------------------------------------------- the generated label

    /**
     * `ARTIKELTEXT` on the aggregated bill-wide Rabatt line is `"Aktion: " + every bill-wide
     * promotion name`. `PromotionTable.name` is varchar(200), so two promotions reach 410
     * characters even though each name is individually legal — the overflow exists only in
     * COMBINATION, so there is no write moment at which it can be rejected.
     *
     * Clamping is admissible here precisely because this is a generated label, not an
     * operator-entered value: the auditable quantity on the line is `STK_BR`, which is untouched,
     * and each promotion stays fully recorded in its own rows. The ellipsis keeps the abbreviation
     * visible instead of silent.
     */
    @Test
    fun `artikeltext label is clamped because it cannot be rejected at any write boundary`() {
        val label = "Aktion: " + listOf("A".repeat(200), "B".repeat(200)).joinToString(", ")
        assertTrue(label.length > DsfinvkFieldLimits.ARTIKELTEXT, "fixture must overflow")

        val clamped = DsfinvkFieldLimits.clampGeneratedLabel(label, DsfinvkFieldLimits.ARTIKELTEXT)

        assertEquals(DsfinvkFieldLimits.ARTIKELTEXT, clamped.length)
        assertTrue(clamped.endsWith("…"), "the cut must be visible, not silent")
        assertTrue(clamped.startsWith("Aktion: AAA"), "the informative prefix must survive")
    }

    @Test
    fun `a label within the limit is left exactly as-is`() {
        val label = "Aktion: Happy Hour"
        assertEquals(
            label,
            DsfinvkFieldLimits.clampGeneratedLabel(label, DsfinvkFieldLimits.ARTIKELTEXT),
            "clamping must never touch a value that already fits",
        )
    }
}
