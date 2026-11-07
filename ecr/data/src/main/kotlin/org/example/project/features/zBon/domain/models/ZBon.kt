/**
 * Exposed DAO entity for ZBon.
 *
 * Provides object-relational mapping for Z-reports with proper relationship
 * management and type-safe database operations. Supports end-of-day
 * reporting and financial reconciliation.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.domain.models

import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.order.database.tables.CancellationTable
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.voucher.database.tables.VoucherRedeemTable
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.features.voucher.domain.models.VoucherRedeem
import org.example.project.features.zBon.database.tables.EmployeeZBonSummaryTable
import org.example.project.features.zBon.database.tables.VatGroupTable
import org.example.project.features.zBon.database.tables.ZBonPaymentSummaryTable
import org.example.project.features.zBon.database.tables.ZBonPromotionSummaryTable
import org.example.project.features.zBon.database.tables.ZBonStammTerminalTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Z-report entity for daily closing reports and financial reconciliation.
 *
 * Manages daily closing reports (Z-reports) including transaction summaries,
 * cash reconciliation, and business performance data. Links to shop and employee
 * information for comprehensive reporting.
 */
class ZBon(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<ZBon>(ZBonTable)

    /** Reference to shop information */
    var shopInfo by ShopInfo referencedOn ZBonTable.shopInfoId

    /** Reference to employee who generated the report */
    var employee by Employee referencedOn ZBonTable.employeeId

    /** Report period start time */
    var startTime by ZBonTable.startTime

    /** Report period end time */
    var endTime by ZBonTable.endTime

    /** Total amount of cancelled/voided transactions */
    var cancelledAmount by ZBonTable.cancelledAmount

    /** Total discount amount aggregated from all bills in this Z-report */
    var totalDiscounts by ZBonTable.totalDiscounts

    /** Total promotional deduction amount aggregated from all bills in this Z-report */
    var totalPromotions by ZBonTable.totalPromotions

    /** Total revenue/turnover for the period */
    var totalPosRevenue by ZBonTable.totalPosRevenue
    var totalReceivableCleared by ZBonTable.totalReceivableCleared

    /** Total tip amount for the period for admin/owner */
    var adminTip by ZBonTable.adminTipRevenue

    /** Starting cash amount, refenrence to kassenbuch entry */
    var openingBalance by ZBonTable.openingBalance

    /** Cash expenditures/outflows */
    var expenses by ZBonTable.expenses

    /** Cash income/inflows */
    var cashRevenue by ZBonTable.cashRevenue

    /** Ending cash amount */
    var closingBalance by ZBonTable.closingBalance

    /** Report creation timestamp */
    var createdAt by ZBonTable.createdAt

    /** Revenue from training bills (tracked separately, not included in totalPosRevenue) */
    var trainingRevenue by ZBonTable.trainingRevenue

    /** Number of training bills in this Z-report period */
    var trainingBillCount by ZBonTable.trainingBillCount

    /** Total refund amount from storno/reverse bills */
    var refundAmount by ZBonTable.refundAmount

    /** Number of storno/reverse bills in this Z-report period */
    var stornoBillCount by ZBonTable.stornoBillCount

    /** Number of Zahlartwechsel Storno bills (Storno + Neubuchung) in this Z-report period. */
    var rebookStornoCount by ZBonTable.rebookStornoCount

    /** Total amount of Zahlartwechsel Storno bills in this Z-report period. */
    var rebookStornoAmount by ZBonTable.rebookStornoAmount

    /** Gapless sequential Z-Bon number per shop */
    var zbonNummer by ZBonTable.zbonNummer

    /*
     * Per-closing Stammdaten snapshot (DSFinV-K Tz. 3.2, audit KABS-2). NULL on every closing
     * created before V95 and on every `ZBon.new { }` that does not set them — the DSFinV-K export
     * then falls back to the live `shopInfo` row. See [ZBonTable] for the full rationale.
     */

    /** Shop name at closing time; NULL = no snapshot. */
    var stammName by ZBonTable.stammName

    /** Street address at closing time. */
    var stammStrasse by ZBonTable.stammStrasse

    /** PLZ at closing time, already padded to 5 digits. */
    var stammPlz by ZBonTable.stammPlz

    /** City at closing time. */
    var stammOrt by ZBonTable.stammOrt

    /** Country at closing time, raw as stored in `shop_info`. */
    var stammLand by ZBonTable.stammLand

    /** Steuernummer at closing time. */
    var stammStnr by ZBonTable.stammStnr

    /** USt-IdNr at closing time. */
    var stammUstId by ZBonTable.stammUstId

    /*
     * Per-closing Stamm_Kassen snapshot (round-7 C3) — an INDEPENDENT group from the shop identity
     * above, with its own discriminator and its own freeze trigger. See [ZBonTable].
     */

    /** When the Kassen/Terminal snapshot was taken; NULL = none, export falls back to live data. */
    var stammKasseSnapshotAt by ZBonTable.stammKasseSnapshotAt

    /** KASSE_BRAND at closing time. */
    var stammKasseBrand by ZBonTable.stammKasseBrand

    /** KASSE_MODELL at closing time. */
    var stammKasseModell by ZBonTable.stammKasseModell

    /** KASSE_SW_BRAND at closing time. */
    var stammKasseSwBrand by ZBonTable.stammKasseSwBrand

    /** KASSE_SW_VERSION at closing time — immune to the startup rewrite of the live config. */
    var stammKasseSwVersion by ZBonTable.stammKasseSwVersion

    /** The terminals that existed when this closing was taken (slaves.csv). */
    val stammTerminals by ZBonStammTerminal referrersOn ZBonStammTerminalTable.zBonId

    /** Reference to bill if that field was null, and assigned to this Z-report */
    val bill by Bill optionalReferrersOn BillTable.zBonId

    /** Reference to cancellations if that field was null, and assigned to this Z-report */
    val cancellations by Cancellation optionalReferrersOn CancellationTable.zBonId

    /** Reference to table session if that field was null, and assigned to this Z-report */
    val tableSession by TableSession optionalReferrersOn TableSessionTable.zBonId

    val employeeSummaries by EmployeeZBonSummary referrersOn EmployeeZBonSummaryTable.zBonId

    val promotionSummaries by ZBonPromotionSummary referrersOn ZBonPromotionSummaryTable.zBonId

    val vouchers by Voucher optionalReferrersOn VoucherTable.zBonId

    val voucherRedeem by VoucherRedeem optionalReferrersOn VoucherRedeemTable.zBonId

    /** Tax groups for this Z-report */
    val vatGroups by VatGroup referrersOn VatGroupTable.zbonId

    /** Payment summaries for this Z-report */
    val paymentSummaries by ZBonPaymentSummary referrersOn ZBonPaymentSummaryTable.zbonId

    val kasseBuch by KassenbuchHeader optionalReferrersOn KassenbuchHeaderTable.zBonId
}
