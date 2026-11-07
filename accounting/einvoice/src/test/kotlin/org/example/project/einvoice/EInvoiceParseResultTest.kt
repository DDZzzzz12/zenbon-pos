package org.example.project.einvoice

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ZUGFeRD MINIMUM file is well-formed and parseable but is deliberately not an EN 16931 invoice —
 * it is a booking aid. Reporting it as [EInvoiceParseResult.Failure] would tell the operator their
 * supplier sent something broken, which is both wrong and unactionable, so it needs its own variant.
 */
class EInvoiceParseResultTest {
    @Test
    fun `a non-conformant profile is distinct from a failure`() {
        val result: EInvoiceParseResult =
            EInvoiceParseResult.NotConformant(
                profile = "urn:factur-x.eu:1p0:minimum",
                reason = "MINIMUM is a booking aid, not an EN 16931 invoice",
            )

        assertEquals(
            EInvoiceParseResult.NotConformant(
                profile = "urn:factur-x.eu:1p0:minimum",
                reason = "MINIMUM is a booking aid, not an EN 16931 invoice",
            ),
            result,
        )
    }
}
