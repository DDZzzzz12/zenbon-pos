package org.example.project.features.zBon.mapper

import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.mapper.toGroupedSummaryDto
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.voucher.mapper.toResponse
import org.example.project.features.voucher.mapper.toSummeryResponse
import org.example.project.features.zBon.domain.EmployeeTipPayoutLedger
import org.example.project.features.zBon.domain.ZBonOwnerTipRule
import org.example.project.features.zBon.domain.models.EmployeeZBonPaymentBreakdown
import org.example.project.features.zBon.domain.models.EmployeeZBonSummary
import org.example.project.features.zBon.domain.models.VatGroup
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.models.ZBonPaymentSummary
import org.example.project.features.zBon.domain.models.ZBonPromotionSummary
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.toDecimalString
import java.math.BigDecimal

/**
 * Extension function to convert ZBon entity to response DTO.
 *
 * Note: This function accesses related entities (shopInfo, employee) and must be called within dbQuery {} context
 * to avoid lazy loading errors.
 *
 * [currency] is the shop's configured ISO 4217 code, resolved once per request by the calling
 * service. It is only consumed by the nested Kassenbuch summaries, which carry it as a response
 * field; it is required rather than defaulted so no caller can silently fall back to "EUR".
 */
fun ZBon.toResponseDto(
    currency: String,
    withKassenBuchData: Boolean = false,
    withTipPayouts: Boolean = false,
): ZBonDto.ZBonResponseDto {
    val legacyClosing = ZBonOwnerTipRule.isLegacyClosing(this)
    val summaries = employeeSummaries.map { it.toResponseDto(legacyClosing) }
    // Employee tips only: an owner tip is revenue and is reported as `adminTip`, so the tip block's
    // total must equal the sum of exactly the summaries it lists (those not marked ownerTip).
    val employeeTipSummaries = summaries.filterNot { it.ownerTip }
    val totalTips =
        if (employeeTipSummaries.isNotEmpty()) {
            employeeTipSummaries.sumOf { it.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO }
        } else {
            null
        }

    val kassenbuchData =
        if (withKassenBuchData) {
            this.kasseBuch.map { it.toGroupedSummaryDto(currency) }
        } else {
            null
        }

    val identity = shopIdentity()

    return ZBonDto.ZBonResponseDto(
        id = id.value,
        zbonNummer = zbonNummer,
        shopInfoId = shopInfo.id.value,
        shopName = identity.name,
        shopAddress = identity.address,
        shopCity = identity.city,
        shopZipCode = identity.zipCode,
        shopCountry = identity.country,
        shopTaxNumber = identity.taxNumber,
        employeeNumber = employee.employeeNumber,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        cancelledAmount = formatDecimal(cancelledAmount),
        refundAmount = formatDecimal(refundAmount),
        stornoBillCount = stornoBillCount,
        rebookStornoCount = rebookStornoCount,
        rebookStornoAmount = formatDecimal(rebookStornoAmount),
        totalDiscounts = formatDecimal(totalDiscounts),
        totalPromotions = formatDecimal(totalPromotions),
        promotionBreakdown = promotionSummaries.map { it.toPromotionBreakdownDto() },
        adminTip = formatDecimal(adminTip),
        totalRevenue = formatDecimal(totalPosRevenue),
        totalReceivableCleared = formatDecimal(totalReceivableCleared),
        openingBalance = formatDecimal(openingBalance),
        expenses = formatDecimal(expenses),
        closingBalance = formatDecimal(closingBalance),
        createdAt = createdAt.toString(),
        vatGroups = vatGroups.map { it.toResponseDto() },
        paymentSummary = paymentSummaries.map { it.toResponseDto() },
        employeeSummaries = summaries,
        vouchers = vouchers.map { it.toSummeryResponse() },
        voucherRedeems = voucherRedeem.map { it.toResponse() },
        totalEmployeeTips = totalTips?.let { formatDecimal(it) },
        trainingRevenue = formatDecimal(trainingRevenue),
        trainingBillCount = trainingBillCount,
        kassenbuchSummaryDto = kassenbuchData,
        // Single-report reads only: a list would pay one ledger query per closing for a figure it
        // never shows.
        tipPayouts =
            if (withTipPayouts) {
                EmployeeTipPayoutLedger.positions(this).map { it.toStatusDto() }
            } else {
                emptyList()
            },
    )
}

/**
 * The shop identity this closing was taken under.
 *
 * A closing snapshots the shop's Stammdaten when it is created (`StammdatenSnapshot.applyTo`, audit
 * KABS-2), or when the shop is edited before the snapshot existed (`freezeUnsnapshottedClosings`).
 * `GET /api/z-bon/{id}` used to ignore that and render the CURRENT `shop_info` row, so an old Z-Bon
 * — and its PDF re-render and reprint, which are built from this DTO — silently moved to the shop's
 * new address after an edit while the DSFinV-K export of the same closing kept the old one.
 *
 * `stammName` is the "snapshot present" discriminator, exactly as in the export. The record is taken
 * **whole** from one source, never coalesced per field: a snapshot whose optional street or tax
 * number is empty was empty at closing time, and filling it from today's row would mix two
 * identities in one header. Only a closing with no snapshot at all falls back to the live row.
 * Requires an ambient transaction (it reads `shopInfo`).
 */
fun ZBon.shopIdentity(): ZBonShopIdentity {
    val snapshotName = stammName
    if (snapshotName == null) {
        return ZBonShopIdentity(
            name = shopInfo.shopName,
            address = shopInfo.shopAddress,
            city = shopInfo.shopCity,
            zipCode = shopInfo.zipCode.toString(),
            country = shopInfo.shopCountry,
            taxNumber = shopInfo.shopTaxNumber ?: "",
        )
    }
    return ZBonShopIdentity(
        name = snapshotName,
        address = stammStrasse.orEmpty(),
        city = stammOrt.orEmpty(),
        zipCode = stammPlz.orEmpty(),
        country = stammLand.orEmpty(),
        taxNumber = stammStnr.orEmpty(),
    )
}

/**
 * [legacyClosing]: whether the closing predates summaries carrying employee tips only
 * ([ZBonOwnerTipRule.isLegacyClosing]); only then can a summary's tip be the owner's.
 */
fun EmployeeZBonSummary.toResponseDto(legacyClosing: Boolean): ZBonDto.EmployeeZBonSummaryResponseDto {
    val emp = employee
    val name = emp.zBonDisplayName()
    return ZBonDto.EmployeeZBonSummaryResponseDto(
        id = id.value,
        employeeNumber = emp.employeeNumber,
        employeeName = name,
        role = emp.role.name,
        amount = this.tipAmount.toDecimalString(),
        totalRevenue = this.totalRevenue.toDecimalString(),
        paymentBreakdown = this.paymentBreakdown.map { it.toResponseDto() },
        ownerTip = ZBonOwnerTipRule.summaryCarriesOwnerTip(this, legacyClosing),
    )
}

/**
 * How a closing names an employee. `Employee.fullName` falls back to the username when there is no
 * personnel record, and the shared login-less device account has none by design — so a station's
 * revenue used to be headed `terminal-device` on the Z-Bon. It gets the account's own internal
 * label instead. Requires an ambient transaction (it reads the personnel record).
 */
fun Employee.zBonDisplayName(): String =
    if (TerminalEmployee.isDeviceAccount(username)) {
        TerminalEmployee.DISPLAY_NAME
    } else {
        fullName.takeIf { it != "Unknown" } ?: employeeNumber
    }

/** One employee's tip-payout position as the Z-Bon detail and the payout answer carry it. */
fun EmployeeTipPayoutLedger.Position.toStatusDto(): ZBonDto.EmployeeTipPayoutStatusDto =
    ZBonDto.EmployeeTipPayoutStatusDto(
        employeeId = employee.id.value.toString(),
        employeeNumber = employee.employeeNumber,
        employeeName = employee.zBonDisplayName(),
        tipTotal = tipTotal.toDecimalString(),
        paidOut = paidOut.toDecimalString(),
        remaining = remaining.toDecimalString(),
        payouts =
            records.map {
                ZBonDto.TipPayoutRecordDto(
                    kassenbuchHeaderId = it.kassenbuchHeaderId,
                    belegNr = it.belegNr,
                    amount = it.amount.toDecimalString(),
                    createdAt = it.createdAt.toString(),
                    reversed = it.reversed,
                    payoutId = it.payoutId,
                    method = it.method,
                )
            },
    )

fun EmployeeZBonPaymentBreakdown.toResponseDto(): ZBonDto.EmployeeZBonPaymentBreakdownResponseDto =
    ZBonDto.EmployeeZBonPaymentBreakdownResponseDto(
        paymentName = this.paymentType.name,
        currency = this.currency,
        amount = formatDecimal(this.amount),
    )

fun VatGroup.toResponseDto(): ZBonDto.VatGroupResponseDto =
    ZBonDto.VatGroupResponseDto(
        vatRate = formatDecimal(this.vat.vatRate.multiply(BigDecimal(100))),
        vatClass = vat.vatClass,
        netAmount = formatDecimal(this.netAmount),
        vatAmount = formatDecimal(this.vatAmount),
        grossAmount = formatDecimal(this.grossAmount),
    )

fun ZBonPaymentSummary.toResponseDto(): ZBonDto.PaymentsSummaryResponseDto =
    ZBonDto.PaymentsSummaryResponseDto(
        paymentName = this.paymentType.name,
        currency = this.currency,
        amount = formatDecimal(this.amount),
    )

fun ZBonPromotionSummary.toPromotionBreakdownDto(): ZBonDto.PromotionBreakdownDto =
    ZBonDto.PromotionBreakdownDto(
        promotionId = promotionId,
        promotionName = promotionName,
        discountType = discountType,
        timesApplied = timesApplied,
        totalDeducted = formatDecimal(totalDeducted),
    )

/**
 * Extension function to convert a list of ZBon entities to response DTOs.
 *
 * Note: This function must be called within dbQuery {} context.
 */
fun List<ZBon>.toResponseDtos(currency: String): List<ZBonDto.ZBonResponseDto> = map { it.toResponseDto(currency) }
