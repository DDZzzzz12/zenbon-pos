package org.example.project.core.utils

import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.setup.Language

/** As [PaymentDto.PaymentSummary.getLocalizedName], for a statistics payment record. */
fun StatisticsDto.PaymentRecordDto.getLocalizedName(language: Language): String =
    localizedPaymentName(language, german = paymentTypeDE, english = paymentTypeEN, chinese = paymentTypeCH)

/**
 * As [PaymentDto.PaymentSummary.getLocalizedName], for a row of the financial overview's payment
 * breakdown (`paymentMethod` is the German name; an older server sends no translations).
 */
fun PaymentMethodSummaryDto.getLocalizedName(language: Language): String =
    localizedPaymentName(language, german = paymentMethod, english = paymentMethodEN, chinese = paymentMethodCH)
