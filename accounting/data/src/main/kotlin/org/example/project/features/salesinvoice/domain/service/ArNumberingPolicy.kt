package org.example.project.features.salesinvoice.domain.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.salesinvoice.ArNumberingConfigDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.BILL_NUMBER_PREFIX
import org.example.project.infrastructure.errors.PosException

/**
 * Checks the numbering configuration before anything issues against it.
 *
 * The collision rule is the reason this exists. Every other misconfiguration here is recoverable by
 * editing a setting; two ranges that can emit the same string are not, because once two documents
 * share an identifier nothing can say which one a payment or an audit refers to. So it is refused
 * at read time, on every read, rather than validated once at write time and trusted afterwards.
 */
object ArNumberingPolicy {
    fun validated(config: ArNumberingConfigDto): ArNumberingConfigDto {
        val prefix = config.prefix.trim()
        if (prefix.isBlank()) {
            throw invalid("The Ausgangsrechnung prefix must not be blank")
        }
        if (prefix.equals(BILL_NUMBER_PREFIX, ignoreCase = true)) {
            throw invalid(
                "The Ausgangsrechnung prefix '$prefix' collides with the Kassenbeleg prefix " +
                    "'$BILL_NUMBER_PREFIX'. Two ranges that can emit the same number are indistinguishable.",
            )
        }
        if (config.startValue < 1L) {
            throw invalid("The Ausgangsrechnung start value must be at least 1, was ${config.startValue}")
        }
        if (config.padding < 1) {
            throw invalid("The Ausgangsrechnung padding must be at least 1, was ${config.padding}")
        }
        return config.copy(prefix = prefix.uppercase())
    }

    private fun invalid(message: String) =
        PosException(
            PosErrorCode.SALES_INVOICE_PREFIX_COLLISION,
            HttpStatusCode.InternalServerError,
            message,
        )
}
