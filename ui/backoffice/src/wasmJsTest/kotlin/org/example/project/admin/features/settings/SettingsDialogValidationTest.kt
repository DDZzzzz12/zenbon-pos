package org.example.project.admin.features.settings

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.sections.PaymentTypeField
import org.example.project.admin.features.settings.presentation.sections.VatRateField
import org.example.project.admin.features.settings.presentation.sections.validatePaymentTypeInput
import org.example.project.admin.features.settings.presentation.sections.validateVatRateInput
import org.example.project.core.domain.enums.setup.GermanVatRate
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The payment-type and VAT-rate dialogs keep Save enabled (option A); these are the per-field errors
 * pressing it on bad input reports instead of calling the API.
 */
class SettingsDialogValidationTest {
    @Test
    fun paymentType_nameIsRequired() {
        assertEquals(mapOf(PaymentTypeField.NAME to CommonKeys.fieldRequired), validatePaymentTypeInput(" "))
        assertTrue(validatePaymentTypeInput("Karte").isEmpty())
    }

    @Test
    fun vatRate_emptyForm_flagsEveryField() {
        assertEquals(
            mapOf(
                VatRateField.VAT_ID to CommonKeys.fieldRequired,
                VatRateField.VAT_CLASS to CommonKeys.fieldRequired,
                VatRateField.RATE to CommonKeys.fieldRequired,
            ),
            validateVatRateInput("", "", null),
        )
    }

    @Test
    fun vatRate_historicalRate_isRefusedWithItsOwnMessage() {
        val historical = GermanVatRate.entries.first { !it.configurable }
        assertEquals(
            mapOf(VatRateField.RATE to AdminStringKeys.SettingsVatRateNotSelectable),
            validateVatRateInput("A", "STANDARD_19", historical),
        )
    }

    @Test
    fun vatRate_completeForm_isSavable() {
        assertTrue(validateVatRateInput("A", "STANDARD_19", GermanVatRate.configurableRates.first()).isEmpty())
    }
}
