package org.example.project.features.matching.data.service

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One contract instalment a payment might settle, reduced to the plain values matching needs.
 *
 * The counterpart of [InvoiceCandidate] for the contract side of the ladder. It carries fields from
 * two rows — the instalment's own [dueDate], [expectedAmount] and [openAmount], and the contract's
 * [supplierId], [sepaMandateId] and tolerance band — because a matcher asking "could this direct
 * debit be this instalment?" needs both, and flattening them here keeps the ranker a pure function
 * over values rather than something that walks Exposed relations.
 *
 * [sepaMandateId] is null on almost every contract today, and signal 3 is silent while it is. That
 * is the correct behaviour: it is the identifier that makes the signal `EXACT`, and guessing in its
 * absence is precisely what this ladder is designed not to do.
 *
 * Money is `BigDecimal`, compared with `compareTo` — never `equals`, which is scale-sensitive, and
 * never a `Double`.
 */
data class ContractCandidate(
    val instalmentId: Long,
    val contractId: Long,
    val supplierId: Long? = null,
    val sepaMandateId: String? = null,
    val expectedAmount: BigDecimal? = null,
    val tolerancePct: BigDecimal? = null,
    val toleranceAbs: BigDecimal? = null,
    val dueDate: LocalDate,
    val openAmount: BigDecimal? = null,
)
