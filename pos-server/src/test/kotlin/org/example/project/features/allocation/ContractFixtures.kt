package org.example.project.features.allocation

import kotlinx.datetime.LocalDateTime
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.features.setup.domain.models.ShopInfo
import java.math.BigDecimal

/**
 * The contract side of an allocation fixture, beside [AllocationFixtures] so the allocation tests
 * build both ends of a row the same way.
 *
 * All of these require an ambient transaction.
 */
internal object ContractFixtures {
    /**
     * One shop-owned contract carrying a single instalment.
     *
     * [expected] lands on the instalment, which is what the residual is computed against — a
     * contract-level `expected_amount` is only the template the generator copies from.
     */
    fun newInstalment(
        expected: BigDecimal?,
        dueDate: LocalDateTime = LocalDateTime(2026, 6, 1, 0, 0),
        name: String = "Miete Ladenlokal",
    ): ContractInstalment {
        val contract =
            Contract.new {
                shop = ShopInfo.all().first()
                this.name = name
                interval = ContractInterval.MONTHLY.name
                dueDay = dueDate.dayOfMonth
                startDate = LocalDateTime(2026, 1, 1, 0, 0)
                expectedAmount = expected
            }
        return ContractInstalment.new {
            this.contract = contract
            this.dueDate = dueDate
            this.expectedAmount = expected
        }
    }
}
