package org.example.project.features.contract.data.service

import kotlinx.datetime.LocalDate
import org.example.project.features.contract.domain.models.ContractAlertKind
import java.math.BigDecimal

/**
 * One thing wrong with one instalment, as concluded from current state.
 *
 * The contract's own identity is not here: the derivation is given a single contract's instalments
 * and knows nothing about which, so the caller adds the id and the name on the way to the wire.
 *
 * [expectedAmount] and [actualAmount] are carried only by `AMOUNT_OFF_BAND` — the two figures are
 * the whole point of that alert, and an operator asked to act on "the amount changed" without being
 * told from what to what will simply open the invoice anyway.
 */
data class ContractAlert(
    val kind: ContractAlertKind,
    val instalmentId: Long,
    val dueDate: LocalDate,
    val expectedAmount: BigDecimal? = null,
    val actualAmount: BigDecimal? = null,
)
