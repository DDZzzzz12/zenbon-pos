package org.example.project.admin.features.contracts.presentation

import org.example.project.admin.core.presentation.format.editableDecimalText
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.utils.toCanonicalMoneyOrNull

/**
 * The editable contract, all as typed text.
 *
 * Money stays a `String` the whole way — typed with a comma if that is what the operator does,
 * canonicalised with [toCanonicalMoneyOrNull] on the way out, and **never** parsed into a `Double`.
 * `dueDay` and `noticePeriodDays` are counts, not money, so they are plain integers.
 */
data class ContractForm(
    val name: String = "",
    val description: String = "",
    val supplierId: String = "",
    val expectedAmount: String = "",
    val tolerancePct: String = "",
    val toleranceAbs: String = "",
    val interval: String = DEFAULT_INTERVAL,
    val dueDay: String = "1",
    /** ISO weekday as text, "" when the interval is not WEEKLY. */
    val dueWeekday: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val noticePeriodDays: String = "",
    val expectsInvoice: Boolean = false,
    val defaultAccountNo: String = "",
    val defaultBuCode: String = "",
    val defaultCostCenter: String = "",
    val isActive: Boolean = true,
) {
    fun toCreateRequest() =
        ContractDto.CreateRequestDto(
            name = name.trim(),
            interval = interval,
            dueDay = dueDay.trim().toInt(),
            dueWeekday = dueWeekday.trim().toIntOrNull().takeIf { interval == "WEEKLY" },
            startDate = startDate.trim(),
            description = description.blankToNull(),
            supplierId = supplierId.trim().toLongOrNull(),
            expectedAmount = expectedAmount.canonicalMoneyOrNull(),
            tolerancePct = tolerancePct.canonicalMoneyOrNull(),
            toleranceAbs = toleranceAbs.canonicalMoneyOrNull(),
            endDate = endDate.blankToNull(),
            noticePeriodDays = noticePeriodDays.trim().toIntOrNull(),
            expectsInvoice = expectsInvoice,
            defaultAccountNo = defaultAccountNo.blankToNull(),
            defaultBuCode = defaultBuCode.blankToNull(),
            defaultCostCenter = defaultCostCenter.blankToNull(),
        )

    fun toUpdateRequest() =
        ContractDto.UpdateRequestDto(
            name = name.trim(),
            interval = interval,
            dueDay = dueDay.trim().toInt(),
            dueWeekday = dueWeekday.trim().toIntOrNull().takeIf { interval == "WEEKLY" },
            startDate = startDate.trim(),
            description = description.blankToNull(),
            supplierId = supplierId.trim().toLongOrNull(),
            expectedAmount = expectedAmount.canonicalMoneyOrNull(),
            tolerancePct = tolerancePct.canonicalMoneyOrNull(),
            toleranceAbs = toleranceAbs.canonicalMoneyOrNull(),
            endDate = endDate.blankToNull(),
            noticePeriodDays = noticePeriodDays.trim().toIntOrNull(),
            expectsInvoice = expectsInvoice,
            defaultAccountNo = defaultAccountNo.blankToNull(),
            defaultBuCode = defaultBuCode.blankToNull(),
            defaultCostCenter = defaultCostCenter.blankToNull(),
            isActive = isActive,
        )

    companion object {
        /**
         * The six the server's `ContractInterval` holds, in the enum's own order. Mirrored rather
         * than imported: the enum lives in core-data, which adminApp does not depend on.
         */
        val INTERVALS = listOf("ONE_TIME", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMIANNUAL", "ANNUAL")

        const val DEFAULT_INTERVAL = "MONTHLY"

        fun from(dto: ContractDto.ResponseDto) =
            ContractForm(
                name = dto.name,
                description = dto.description.orEmpty(),
                supplierId = dto.supplierId?.toString().orEmpty(),
                expectedAmount = dto.expectedAmount.orEmpty().asEditableAmount(),
                tolerancePct = dto.tolerancePct.orEmpty().asEditableAmount(),
                toleranceAbs = dto.toleranceAbs.orEmpty().asEditableAmount(),
                interval = dto.interval,
                dueDay = dto.dueDay.toString(),
                dueWeekday = dto.dueWeekday?.toString().orEmpty(),
                startDate = dto.startDate.substringBefore('T'),
                endDate = dto.endDate?.substringBefore('T').orEmpty(),
                noticePeriodDays = dto.noticePeriodDays?.toString().orEmpty(),
                expectsInvoice = dto.expectsInvoice,
                defaultAccountNo = dto.defaultAccountNo.orEmpty(),
                defaultBuCode = dto.defaultBuCode.orEmpty(),
                defaultCostCenter = dto.defaultCostCenter.orEmpty(),
                isActive = dto.isActive,
            )
    }
}

/**
 * A server decimal (`149.0000`) as the operator edits it: minimal scale of 2 and the German comma
 * (`149,00`). [toCanonicalMoneyOrNull] accepts either separator, so the value is unchanged on the way out.
 * A missing amount is blank.
 */
internal fun String?.asEditableAmount(): String =
    this
        ?.let { raw -> editableDecimalText(raw, minFractionDigits = 2) }
        ?.replace('.', ',')
        .orEmpty()

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun String.canonicalMoneyOrNull(): String? = blankToNull()?.toCanonicalMoneyOrNull()
