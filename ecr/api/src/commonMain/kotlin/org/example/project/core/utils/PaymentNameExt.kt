package org.example.project.core.utils

import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.setup.Language

/**
 * Returns the localized payment type name based on the current language.
 *
 * Falls back to the German name when the requested language translation is missing or blank.
 *
 * @param language The target language for the payment name
 * @return The localized payment type name
 */
fun PaymentDto.PaymentSummary.getLocalizedName(language: Language): String =
    localizedPaymentName(language, german = paymentTypeDE, english = paymentTypeEN, chinese = paymentTypeCH)

/** As [PaymentDto.PaymentSummary.getLocalizedName], for a bill's payment (`paymentTypeName` is the German name). */
fun PaymentDto.PaymentResponse.getLocalizedName(language: Language): String =
    localizedPaymentName(language, german = paymentTypeName, english = paymentTypeEN, chinese = paymentTypeCH)

/**
 * The one rule every payment-type name follows: the translation for [language], else (missing or
 * blank) the German master name. Public so a client-local payment-type model (the till's offline
 * `PaymentType`) names a tender exactly as the DTO extensions above do.
 */
fun localizedPaymentName(
    language: Language,
    german: String,
    english: String?,
    chinese: String?,
): String {
    val translated =
        when (language) {
            Language.ENGLISH -> english
            Language.GERMAN -> german
            Language.CHINESE -> chinese
        }
    return translated?.takeIf { it.isNotBlank() } ?: german
}
