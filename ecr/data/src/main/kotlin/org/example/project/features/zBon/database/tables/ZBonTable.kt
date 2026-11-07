/**
 * Exposed table definition for Z-reports (ZBon).
 *
 * Defines the database schema structure for daily closing reports with proper
 * column types and constraints. Supports end-of-day reporting and financial
 * reconciliation for compliance and business management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.zBon.database.tables

import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime
import java.math.BigDecimal

/**
 * Exposed table definition for zbon table.
 *
 * Manages daily closing reports (Z-reports) including transaction summaries,
 * cash reconciliation, and business performance data. Links to shop and employee
 * information for comprehensive reporting.
 */
object ZBonTable : LongIdTable("zbon", "zbon_id") {
    /** Reference to shop information */
    val shopInfoId = reference("shop_info_id", ShopInfoTable)

    /** Reference to employee who generated the report */
    val employeeId = reference("employee_id", EmployeeTable)

    /** Report period start time */
    val startTime = datetime("start_time")

    /** Report period end time */
    val endTime = datetime("end_time")

    /** Total amount of cancelled/voided transactions */
    val cancelledAmount = decimal("cancelled_amount", precision = 19, scale = 4)

    /** Total discount amount aggregated from all bills in this Z-report */
    val totalDiscounts = decimal("total_discounts", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Total promotional deduction amount aggregated from all bills in this Z-report */
    val totalPromotions = decimal("total_promotions", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Total revenue/turnover for the period */
    val totalPosRevenue = decimal("total_pos_revenue", precision = 19, scale = 4)

    /** 4c-2c: gross of receivables cleared at the till this closing — NOT revenue, shown separately. */
    val totalReceivableCleared = decimal("total_receivable_cleared", precision = 19, scale = 4).default(java.math.BigDecimal.ZERO)

    val adminTipRevenue = decimal("admin_tip_revenue", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Starting cash amount */
    val openingBalance = decimal("opening_balance", precision = 19, scale = 4)

    /** Cash expenditures/outflows */
    val expenses = decimal("expenses", precision = 19, scale = 4)

    val cashRevenue = decimal("cash_revenue", precision = 19, scale = 4)

    /** Ending cash amount */
    val closingBalance = decimal("closing_balance", precision = 19, scale = 4)

    /** Report creation timestamp */
    val createdAt = datetime("created_at")

    /** Revenue from training bills (tracked separately, not included in totalPosRevenue) */
    val trainingRevenue = decimal("training_revenue", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Number of training bills in this Z-report period */
    val trainingBillCount = integer("training_bill_count").default(0)

    /** Total refund amount from storno/reverse bills */
    val refundAmount = decimal("refund_amount", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Number of storno/reverse bills in this Z-report period */
    val stornoBillCount = integer("storno_bill_count").default(0)

    /** Number of Zahlartwechsel Storno bills (Storno + Neubuchung) in this Z-report period. */
    val rebookStornoCount = integer("rebook_storno_count").default(0)

    /** Total amount of Zahlartwechsel Storno bills in this Z-report period. */
    val rebookStornoAmount = decimal("rebook_storno_amount", precision = 19, scale = 4).default(BigDecimal.ZERO)

    /** Gapless sequential Z-Bon number per shop (regulatory requirement) */
    val zbonNummer = long("zbon_nummer").default(0)

    /*
     * Per-closing Stammdaten snapshot — DSFinV-K Tz. 3.2 (p.23), audit KABS-2.
     *
     * The shop master data that applied WHEN THIS CLOSING WAS TAKEN. Without it the export
     * re-reads the live `shop_info` row for every Z_NR, so a later address/tax-id edit silently
     * rewrites the bytes of an already-delivered Kassenabschluss.
     *
     * ALL NULLABLE ON PURPOSE. NULL means "no snapshot" and the export falls back to the live
     * row — today's behaviour. That keeps 30+ test files constructing `ZBon.new { }` working, and
     * it is the honest representation of a pre-V95 closing: fabricating a snapshot for a closing
     * whose Stammdaten we cannot actually reconstruct would be worse than admitting we do not know.
     * [stammName] is the "snapshot present" discriminator.
     *
     * These are written per insert in `ZBonRepositoryImpl.createReport`, NOT via `default(...)` —
     * `Column.default(value)` evaluates once at table-object init (audit KABS-1) and would freeze
     * one shop's data across every future closing.
     */

    /** Shop name at closing time; NULL = no snapshot, fall back to the live row. */
    val stammName = varchar("stamm_name", 255).nullable()

    /** Street address at closing time. */
    val stammStrasse = varchar("stamm_strasse", 500).nullable()

    /**
     * PLZ at closing time, stored ALREADY LEFT-PADDED to 5 digits. `shop_info.zip_code` is an
     * INTEGER that drops the leading zero (Dresden 01067 → 1067); capturing the padded string here
     * avoids re-deriving it from a lossy value on every export.
     *
     * Padded, never TRUNCATED (round-7 C8). `zip_code` is an INTEGER and
     * `InitialSetupDto.ShopSetup.validate()` admits 3..10 digits, so a >5-digit value is reachable.
     * It is already invalid as a German PLZ, but shortening it would fabricate a different,
     * plausible PLZ on a delivered Kassenabschluss — hence the column is 10 wide and every writer
     * uses `padStart(5, '0')` semantics. V95's `LPAD` truncated; V97 repairs its output.
     */
    val stammPlz = varchar("stamm_plz", 10).nullable()

    /** City at closing time. */
    val stammOrt = varchar("stamm_ort", 100).nullable()

    /** Country at closing time, stored RAW as in `shop_info` — ISO alpha-3 mapping stays at the export seam. */
    val stammLand = varchar("stamm_land", 100).nullable()

    /** Steuernummer at closing time. STNR and USTID are ALTERNATIVES (audit STAM-8) — never copied into each other. */
    val stammStnr = varchar("stamm_stnr", 50).nullable()

    /** § 27a UStG USt-IdNr at closing time. */
    val stammUstId = varchar("stamm_ustid", 15).nullable()

    /*
     * Per-closing Stamm_Kassen snapshot — DSFinV-K Tz. 3.2 (p.23), audit round-7 C3.
     *
     * A SECOND, independent snapshot group. The columns above capture the SHOP identity
     * (cashpointclosing.csv / location.csv, round-5 KABS-2); these capture the CASH REGISTER
     * identity that cashregister.csv emits, plus — via `zbon_stamm_terminals` — the terminals
     * slaves.csv emits. Both are Stammdaten of the same closing, but they have different sources
     * (`shop_info` vs. the `KASSEN_MELDUNG_CONFIG` setting + the `terminals` table) and different
     * freeze triggers, so they carry their own discriminators and are each written all-or-nothing.
     *
     * ONLY the genuinely per-closing fields live here. `Z_KASSE_ID`, `Z_NR`, `Z_ERSTELLUNG` and
     * `TERMINAL_ID` are JOIN KEYS across every DSFinV-K file and must NOT be snapshotted — this
     * implementation derives `Z_KASSE_ID` from `kasseSeriennr`, the same value `KASSE_SERIENNR`
     * carries, so freezing the serial per closing would desynchronise it from the join key and
     * break the joins. `KASSE_BASISWAEH_CODE` is a literal and `KEINE_UST_ZUORDNUNG` is already
     * derived from the closing's own rows.
     */

    /**
     * When the Stamm_Kassen/Stamm_Terminals snapshot was taken; NULL = never taken, and the export
     * falls back to the live config + live terminals exactly as it did before round-7 C3.
     *
     * A dedicated marker rather than "any snapshot column is non-null": a closing may legitimately
     * have had ZERO terminals, so presence of `zbon_stamm_terminals` rows cannot discriminate
     * "snapshotted, and there were none" from "never snapshotted".
     *
     * Written per row at snapshot time, never `default(...)`/`currentTimeStamp()` as a column
     * default — that evaluates once at table-object init and freezes one instant forever (KABS-1).
     */
    val stammKasseSnapshotAt = datetime("stamm_kasse_snapshot_at").nullable()

    /** KASSE_BRAND at closing time (manufacturer of the Aufzeichnungssystem). */
    val stammKasseBrand = varchar("stamm_kasse_brand", 50).nullable()

    /** KASSE_MODELL at closing time. */
    val stammKasseModell = varchar("stamm_kasse_modell", 50).nullable()

    /** KASSE_SW_BRAND at closing time. */
    val stammKasseSwBrand = varchar("stamm_kasse_sw_brand", 50).nullable()

    /**
     * KASSE_SW_VERSION at closing time.
     *
     * `ServerManager.updateKassenSwVersion` rewrites the live `KASSEN_MELDUNG_CONFIG` value on
     * EVERY server start, so without this column a plain binary upgrade changed the exported bytes
     * of every historical closing. A closing signed under software version X must keep reporting X.
     */
    val stammKasseSwVersion = varchar("stamm_kasse_sw_version", 50).nullable()

    init {
        uniqueIndex("uq_zbon_shop_nummer", shopInfoId, zbonNummer)
    }
}
