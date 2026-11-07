package org.example.project.features.contract.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.document.domain.models.Document
import org.example.project.features.supplier.domain.models.Supplier
import java.math.BigDecimal

/**
 * The editable fields of a contract, already parsed and resolved.
 *
 * A parameter object rather than seventeen positional arguments on both `create` and `update`, and
 * it is what keeps the parsing (money strings, ISO dates, the interval name) and the reference
 * lookups in the service, where a bad value can still become a 400, instead of in the repository.
 */
data class ContractValues(
    val name: String,
    val interval: ContractInterval,
    val dueDay: Int,
    /** ISO-8601 weekday, 1 = Monday .. 7 = Sunday. Required for WEEKLY, null for every other interval. */
    val dueWeekday: Int? = null,
    val startDate: LocalDateTime,
    val description: String? = null,
    val supplier: Supplier? = null,
    val expectedAmount: BigDecimal? = null,
    val tolerancePct: BigDecimal? = null,
    val toleranceAbs: BigDecimal? = null,
    val endDate: LocalDateTime? = null,
    val noticePeriodDays: Int? = null,
    val expectsInvoice: Boolean = false,
    /** SEPA mandate reference; already blank-normalised to null by the service. */
    val sepaMandateId: String? = null,
    val defaultAccountNo: String? = null,
    val defaultBuCode: String? = null,
    val defaultCostCenter: String? = null,
    val document: Document? = null,
    val isActive: Boolean = true,
)
