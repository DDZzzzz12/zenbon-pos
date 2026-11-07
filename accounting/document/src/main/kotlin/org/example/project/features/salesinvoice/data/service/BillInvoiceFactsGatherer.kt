package org.example.project.features.salesinvoice.data.service

import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.promotion.database.tables.BillPromotionTable
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.salesinvoice.domain.service.BillInvoiceFacts
import org.example.project.features.salesinvoice.domain.service.BillInvoiceLineFact
import org.example.project.features.salesinvoice.domain.service.BillInvoicePromotionFact
import org.example.project.features.salesinvoice.domain.service.BillInvoiceVatFact
import org.example.project.features.salesinvoice.domain.service.DraftTseBlock
import org.example.project.features.salesinvoice.domain.service.InvoiceSeller
import org.example.project.features.salesinvoice.domain.service.LiveSalesInvoiceLookup
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.domain.models.TseTransaction
import org.jetbrains.exposed.v1.core.*
import java.math.BigDecimal

/**
 * Reads one bill aggregate into the flat facts the mapper consumes. Requires an ambient transaction.
 *
 * Exists so [org.example.project.features.salesinvoice.domain.service.BillToInvoiceMapper] can stay
 * pure: every lazy-loaded association is resolved here, in one place, rather than inside rules that
 * should be testable without a database.
 */
class BillInvoiceFactsGatherer {
    fun gather(billId: Long): BillInvoiceFacts? {
        val bill = Bill.findById(billId) ?: return null
        val shop = ShopInfo.all().firstOrNull() ?: return null

        return BillInvoiceFacts(
            billId = billId,
            billNumber = bill.billNumber,
            billAmount = bill.billAmount,
            paidAt = bill.endTime,
            kind = bill.kind(),
            // A Storno bill carries the reason too; only the ORIGINAL is "cancelled" (BillRelationLoader).
            isCancelled = bill.deletingReason != null && bill.readValues[BillTable.deletedBillId] == null,
            isTraining = bill.isTraining,
            hasLiveInvoice = LiveSalesInvoiceLookup.exists(billId),
            lines =
                bill.items
                    .sortedBy { it.id.value }
                    .map {
                        BillInvoiceLineFact(
                            orderItemId = it.id.value,
                            itemName = it.product.name,
                            grossAmount = it.finalPrice,
                            vatRateFraction = it.vat.vatRate,
                            vatId = it.vat.id.value,
                        )
                    },
            vatRows =
                bill.vatDetails.map {
                    BillInvoiceVatFact(
                        vatRateFraction = it.vat.vatRate,
                        netAmount = it.netAmount,
                        vatAmount = it.vatAmount,
                        totalAmount = it.totalAmount,
                        vatId = it.vat.id.value,
                    )
                },
            // Only an OWNER tip is in the VAT rows; an EMPLOYEE tip never is (no VAT row).
            ownerTip = if (bill.tipRecipient == TipRecipient.OWNER) bill.tips else BigDecimal.ZERO,
            promotionAmount = bill.promotionAmount,
            // What the bill STORED it deducted, in the order it applied them — never re-evaluated.
            promotions =
                BillPromotion
                    .find { BillPromotionTable.billId eq billId }
                    .orderBy(BillPromotionTable.id to SortOrder.ASC)
                    .map {
                        BillInvoicePromotionFact(
                            billPromotionId = it.id.value,
                            name = it.promotion.name,
                            deductedAmount = it.deductedAmount,
                            orderItemId = it.readValues[BillPromotionTable.orderItemId]?.value,
                        )
                    },
            tse = bill.tseTransaction?.toDraftBlock(),
            seller = sellerFrom(shop),
        )
    }

    /** Snapshotted rather than referenced: `shop_info` is editable and an issued invoice is not. */
    fun sellerFrom(shop: ShopInfo) =
        InvoiceSeller(
            name = shop.companyName?.takeIf { it.isNotBlank() } ?: shop.shopName,
            vatId = shop.ustIdNr,
            taxNumber = shop.shopTaxNumber,
            street = shop.shopAddress,
            zip = shop.zipCode.toString(),
            city = shop.shopCity,
            country = shop.shopCountry,
        )

    /** Read off the bill's own foreign-key columns — no related row is loaded to classify it. */
    private fun Bill.kind(): BillKind =
        BillKind.of(
            reversesBill = readValues[BillTable.deletedBillId] != null,
            rebooksBill = readValues[BillTable.rebooksBillId] != null,
            clearsInvoice = readValues[BillTable.clearsSalesInvoiceId] != null,
        )

    private fun TseTransaction.toDraftBlock() =
        DraftTseBlock(
            serialNumber = tseSerialNumber,
            transactionNumber = transactionNumber,
            signatureCounter = signatureCounter,
            signature = signature,
            signatureAlgorithm = signatureAlgorithm,
            logTimeFormat = logTimeFormat,
            processType = processType,
            startTime = startTime,
            endTime = endTime,
            timestamp = tseTimestamp,
            startTimestamp = tseStartTimestamp,
        )
}
