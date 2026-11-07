@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.bill.mapper

import kotlinx.datetime.toInstant
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.features.bill.domain.RebookEligibility
import org.example.project.features.bill.domain.RebookFacts
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.bill.domain.service.BillInvoiceLink
import org.example.project.features.order.mapper.toGroupedOrderItemResponseDto
import org.example.project.features.payment.mapper.toResponse
import org.example.project.features.payment.mapper.toSummeryDto
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.mapper.toResponseDto
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.utils.builder.tse.toQrCodeDataDto
import org.example.project.utils.builder.tse.toTseQrCodeContent
import org.example.project.utils.builder.tse.toTseReceiptData
import org.example.project.utils.formatter.formatDecimal
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.core.eq

/**
 * Extension function to convert Bill entity to BillResponseDto.
 *
 * [relations] defaults to a single-bill [BillRelationLoader] read; a list caller should batch-load
 * them once for the whole list and pass each bill's entry.
 */
fun Bill.toResponseDto(
    invoices: BillInvoiceLink,
    relations: BillDto.BillRelationsDto = BillRelationLoader.load(listOf(this.id.value), invoices).getValue(this.id.value),
): BillDto.BillResponseDto {
    val liveInvoice = invoices.liveInvoiceFor(this.id.value)
    return BillDto.BillResponseDto(
        billId = this.id.value,
        employeeId =
            this.employeeId.id.value
                .toString(),
        employeeNumber = this.employeeId.employeeNumber,
        tipRecipient = this.tipRecipient.name,
        tableSessionId = this.tableSession.id.value,
        tableNumber = this.tableSession.table.tableNumber,
        billNumber = this.billNumber,
        billAmount = formatDecimal(this.billAmount),
        discountAmount = formatDecimal(this.discountAmount),
        promotionAmount = formatDecimal(this.promotionAmount),
        promotions = BillPromotion.find { BillPromotionTable.billId eq this@toResponseDto.id }.map { it.toResponseDto() },
        paidAmount = formatDecimal(this.paidAmount),
        tips = formatDecimal(this.tips),
        startTime = this.startTime.toString(),
        endTime = this.endTime.toString(),
        deletedBillId = if (this.deletedBill != null) this.deletedBill!!.billNumber else null,
        deletingReason = this.deletingReason,
        voucherRedeem = this.payments.mapNotNull { it.voucherRedeem }.map { it.toRedeemResponse() },
        tseTransactionId = this.tseTransaction?.id?.value,
        // B2: in SIMULATION mode the driver returns a fabricated signature ("SIM-<millis>-…") and
        // the literal public key "SIMULATED_PUBLIC_KEY". Anhang I p. 122 makes the QR a SUBSTITUTE
        tseQrCodeContent = this.tseTransaction?.toTseQrCodeContent(),
        // What the printed Beleg shows instead of the QR (AEAO Nr. 2.4.4), under the same guard.
        tseReceiptData = this.tseTransaction?.toTseReceiptData(),
        vatDetails = this.vatDetails.map { it.toResponse() },
        orderItems =
            if (this.deletedBill == null) {
                this.items.toList().toGroupedOrderItemResponseDto()
            } else {
                this.deletedBill!!
                    .items
                    .toList()
                    .toGroupedOrderItemResponseDto()
            },
        payments =
            if (this.deletedBill != null) {
                this.refundPayments.map { it.toResponse() }
            } else {
                this.payments.map { it.toResponse() }
            },
        // Mirrors rebookBill's guards: a closed day and an ISSUED invoice refuse it as well as the
        // eligibility rule — a draft does not, the rebook deletes it with the Storno.
        isRebookable =
            this.zBon == null &&
                liveInvoice?.issued != true &&
                RebookEligibility.refusal(this.rebookFacts()) == null,
        stornoReason = this.stornoReason,
        // Mirrors BillServiceImpl.deleteBill's guards: it refuses a closed day, an already-storno'd bill
        // and a bill with an ISSUED invoice (a draft is deleted with the Storno).
        isDeletable =
            this.zBon == null &&
                this.deletingReason == null &&
                this.deletedBill == null &&
                liveInvoice?.issued != true,
        firstOrderTseStartTime = this.getFirstOrderTseStartTime(),
        salesInvoiceId = liveInvoice?.id,
        salesInvoiceNumber = liveInvoice?.invoiceNumber,
        salesInvoiceOpenAmount = liveInvoice?.openAmount?.let { formatDecimal(it) },
        relations = relations,
        isTraining = this.isTraining,
    )
}

/** Reads [RebookFacts] from a bill entity; must run inside a transaction. */
fun Bill.rebookFacts(): RebookFacts {
    val location = this.tableSession.table.locationTypeRef
    return RebookFacts(
        isStandingSale = this.deletingReason == null && this.deletedBill == null,
        isVirtualTable = !location.isTakeaway && location.isVirtual,
        hasVoucherItem = this.items.any { it.product.productNumber == "VOU" },
        clearsInvoice = this.clearsSalesInvoiceId != null,
    )
}

/** Converts a list of bills, batch-loading their relations once ([BillRelationLoader]) instead of per bill. */
fun List<Bill>.toResponseDtos(invoices: BillInvoiceLink): List<BillDto.BillResponseDto> {
    val relations = BillRelationLoader.load(this.map { it.id.value }, invoices)
    return this.map { bill -> bill.toResponseDto(invoices, relations.getValue(bill.id.value)) }
}

fun BillVatDetail.toResponse(): BillDto.BillVatDetailDto =
    BillDto.BillVatDetailDto(
        id = this.id.value,
        vatId = this.vat.id.value,
        vatRate = this.vat.vatRate.toDouble(),
        vatAmount = formatDecimal(this.vatAmount),
        netAmount = formatDecimal(this.netAmount),
        totalAmount = formatDecimal(this.totalAmount),
    )

/**
 * Converts a list of bills to list rows, batch-loading the bill relations once for the whole list
 * ([BillRelationLoader]): a fixed number of queries, never one per row.
 */
fun List<Bill>.toSummaryDtos(invoices: BillInvoiceLink): List<BillDto.BillSummaryDto> {
    val relations = BillRelationLoader.load(this.map { it.id.value }, invoices)
    return this.map { bill -> bill.toSummaryDto(relations.getValue(bill.id.value)) }
}

/**
 * The list row for one bill. A Storno lists what it refunded, every other bill its own payment rows.
 */
private fun Bill.toSummaryDto(relations: BillDto.BillRelationsDto): BillDto.BillSummaryDto {
    val session = this.tableSession
    val table = session.table
    val locationType = table.locationTypeRef
    val refunds = this.refundPayments.toList()
    val rowPayments = refunds.ifEmpty { this.payments.toList() }
    return BillDto.BillSummaryDto(
        billId = this.id.value,
        billNumber = this.billNumber,
        billAmount = formatDecimal(this.billAmount),
        paidAmount = formatDecimal(this.paidAmount),
        paymentType = rowPayments.map { it.toSummeryDto() },
        tips = formatDecimal(this.tips),
        endTime = this.endTime.toString(),
        employeeNumber = this.employeeId.employeeNumber,
        tableNumber = table.tableNumber,
        tableType = if (locationType.isVirtual) locationType.name else null,
        tableTypeEN = if (locationType.isVirtual) locationType.englishName else null,
        tableTypeCH = if (locationType.isVirtual) locationType.chineseName else null,
        relations = relations,
        isTraining = this.isTraining,
    )
}

/**
 * Extension function to convert VoucherRedeem entity to RedeemVoucherResponse DTO.
 */
fun VoucherRedeem.toRedeemResponse(): VoucherDto.RedeemVoucherResponse =
    VoucherDto.RedeemVoucherResponse(
        id = this.id.value,
        voucherNumber = this.voucherNumber,
        voucherSource = this.voucherType,
        redeemedAmount = this.redeemAmount.toString(),
        remainingValue = this.voucher?.remainingValue.toString(),
        redeemedAt = this.redeemedAt.toString(),
        billId = this.payment.bill.id.value,
        paymentId = this.payment.id.value,
    )

fun Bill.toTseQrCodeDataDto(): BillDto.TseQrCodeDataDto? = this.tseTransaction?.toQrCodeDataDto()

/**
 * The Tz.-2.7.2 relief PRINT value: the first Bestellung's StartTransaction log time.
 *
 * Under the Tz.-2.7.2 Erleichterung (payment-time Kassenbeleg + per-order Bestellung-V1
 * signing) the receipt must READABLY disclose when ordering began — i.e. the first order's
 * TSE *StartTransaction* log time, not the bill's own transaction time and not a finish
 * time. This is the value the Beleg prints (see BillFormatter, Anhang I p. 122 / round-3
 * TSER-3); the bill's own QR still carries the bill transaction's own timestamps (audit
 * TSER-14), so this stays a separate print-only field.
 *
 * M9: the first order is resolved from ALL orders, not only signed ones. Filtering to orders that
 * carry a TSE transaction broke in two ways whenever the TSE was down during ordering — and the
 * quieter failure was the more dangerous:
 *
 *  - if a LATER order signed after the TSE recovered, the unsigned first order was skipped and the
 *    Beleg printed the later signature, asserting that ordering began after it actually did;
 *  - if NO order signed, this returned null and the receipt dropped the line altogether, leaving
 *    the Tz.-2.7.2 precondition unmet while the relief was still being claimed.
 *
 * So: take the genuinely first order, prefer its TSE StartTransaction time, and fall back to the
 * till's own capture time (`orderTime`) when the TSE never recorded it. An approximate disclosure
 * is a much smaller problem than a confidently wrong one or a missing one.
 *
 * @return Unix timestamp in seconds of the first order's TSE StartTransaction — or, when that
 *         order was never signed, its capture time. Null only when the Vorgang has no orders.
 */
fun Bill.getFirstOrderTseStartTime(): Long? {
    val firstOrder = this.tableSession.orders.minByOrNull { it.orderTime } ?: return null
    val firstOrderTse = firstOrder.tseTransaction

    // Prefer the persisted StartTransaction log time (seconds); fall back to the finish log
    // time and finally the server-clock startTime, decoded in the zone it was WRITTEN in —
    // the shop zone (currentTimeStamp() → TimezoneConfig, getApplicationTimeZone()), not UTC
    // and not the JVM default (round-3 STAM-2/TSER-10).
    return firstOrderTse?.tseStartTimestamp
        ?: firstOrderTse?.tseTimestamp
        ?: firstOrderTse?.startTime?.toInstant(getTimeZone())?.epochSeconds
        ?: firstOrder.orderTime.toInstant(getTimeZone()).epochSeconds
}
