/**
 * Container object for all DSFinV-K (Digitale Schnittstelle der Finanzverwaltung für Kassensysteme)
 * export-related DTOs.
 *
 * Organizes DTOs for German fiscal compliance CSV export including:
 * - Export request/response DTOs
 * - Validation and statistics DTOs
 * - CSV row DTOs for each DSFinV-K required file
 *
 * DSFinV-K is the German standard for digital interfaces in cash register systems,
 * required for Kassennachschau (fiscal audit) and IDEA audit software integration.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.dsfinvk

import kotlinx.serialization.Serializable

/**
 * Container object for DSFinV-K export DTOs.
 *
 * Groups all data transfer objects related to German fiscal compliance
 * CSV export functionality for tax authority requirements.
 */
object DsfinvkExportDto {
    // ============================================
    // Request/Response DTOs
    // ============================================

    /**
     * Request DTO for initiating a DSFinV-K export.
     *
     * Specifies the date range and export options for generating
     * the required CSV files for fiscal compliance.
     *
     * @property startDate Start date for export (ISO 8601 format: yyyy-MM-dd)
     * @property endDate End date for export (ISO 8601 format: yyyy-MM-dd)
     * @property includeTseTar Whether to include TSE TAR archive in the export
     * @property cashRegisterId Optional specific cash register ID to export
     */
    @Serializable
    data class ExportRequestDto(
        val startDate: String,
        val endDate: String,
        val includeTseTar: Boolean = false,
        /**
         * Only with [includeTseTar]: proceed WITHOUT the TSE TAR when the TSE export fails
         * (audit H10 — the default is to fail loudly instead of silently shipping a package
         * the operator believes is complete).
         */
        val allowMissingTseTar: Boolean = false,
        val cashRegisterId: String? = null,
    ) {
        /**
         * Validates the export request parameters.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(startDate.isNotBlank()) { "Start date is required" }
            require(endDate.isNotBlank()) { "End date is required" }
            require(startDate <= endDate) { "Start date must be before or equal to end date" }
        }
    }

    /**
     * Response DTO for a successful DSFinV-K export.
     *
     * Contains the export results including file information
     * and statistics about the exported data.
     *
     * @property exportId Unique identifier for this export
     * @property fileName Generated file name
     * @property fileSize Size of the export file in bytes
     * @property statistics Export statistics
     * @property generatedAt Timestamp when export was generated
     * @property startDate Export period start date
     * @property endDate Export period end date
     * @property filesIncluded List of CSV files included in the export
     */
    @Serializable
    data class ExportResultDto(
        val exportId: String,
        val fileName: String,
        val fileSize: Long,
        val statistics: ExportStatisticsDto,
        val generatedAt: String,
        val startDate: String,
        val endDate: String,
        val filesIncluded: List<String>,
    )

    /**
     * DTO for export statistics.
     *
     * Contains counts and totals for all exported data
     * to provide overview of export contents.
     *
     * @property totalZBons Number of Z-reports (daily closings)
     * @property totalTransactions Number of transactions (receipts)
     * @property totalLineItems Number of line items
     * @property totalPayments Number of payments
     * @property totalTseTransactions Number of TSE-signed transactions
     * @property totalRevenue Total revenue in the export period
     * @property totalVatAmount Total VAT amount
     * @property currenciesIncluded List of currencies in the export
     */
    @Serializable
    data class ExportStatisticsDto(
        val totalZBons: Int,
        val totalTransactions: Int,
        val totalLineItems: Int,
        val totalPayments: Int,
        val totalTseTransactions: Int,
        val totalRevenue: String,
        val totalVatAmount: String,
        val currenciesIncluded: List<String>,
    )

    /**
     * Response DTO for export data validation.
     *
     * Contains validation results before attempting an export
     * to identify potential issues with the data.
     *
     * @property isValid Whether the data is valid for export
     * @property canExport Whether export can proceed despite warnings
     * @property errors Critical errors that prevent export
     * @property warnings Non-critical issues that should be reviewed
     * @property missingTseCount Number of transactions without TSE signatures
     * @property unclosedZBonCount Number of unclosed Z-reports
     * @property dateRange Validated date range
     */
    @Serializable
    data class ValidationResultDto(
        val isValid: Boolean,
        val canExport: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val missingTseCount: Int,
        val unclosedZBonCount: Int,
        val dateRange: String,
    )

    // ============================================
    // CSV Row DTOs - cashpointclosing.csv
    // ============================================

    /**
     * DTO for cashpointclosing.csv row (Z-Bon/daily closing).
     *
     * Represents a single Z-report entry in DSFinV-K format.
     * Maps to ZBonTable data.
     */
    @Deprecated("Superseded by StammAbschlussRowDto. Fields NACHLASS/BRUTTO/STORNO are not part of DSFinV-K Stamm_Abschluss spec 3.2.1.")
    @Serializable
    data class CashpointClosingRowDto(
        val zKasseId: String, // Z_KASSE_ID - Cash register ID
        val zErstellung: String, // Z_ERSTELLUNG - Creation timestamp
        val zNr: Long, // Z_NR - Z-report number
        val zBuchungstag: String, // Z_BUCHUNGSTAG - Booking date
        val zStartzeit: String, // Z_STARTZEIT - Period start
        val zEndzeit: String, // Z_ENDZEIT - Period end
        val brutto: String, // BRUTTO - Gross revenue
        val storno: String, // STORNO - Cancelled amount
        val nachlass: String, // NACHLASS - Discounts
        val zZaehlerStand: Long, // Z_ZAEHLER_STAND - Counter state
    )

    // ============================================
    // CSV Row DTOs - cashregister.csv
    // ============================================

    /**
     * DTO for cashregister.csv row (POS terminal info).
     *
     * Represents cash register/POS terminal information.
     * Maps to ShopInfoTable data.
     */
    @Deprecated("Superseded by StammKassenRowDto. Use the correct field structure per DSFinV-K spec 3.2.3.")
    @Serializable
    data class CashRegisterRowDto(
        val zKasseId: String, // Z_KASSE_ID - Cash register ID
        val kasseSerienNr: String, // KASSE_SERIENNR - Serial number
        val kasseMarke: String, // KASSE_MARKE - Brand/manufacturer
        val kasseModell: String, // KASSE_MODELL - Model
        val kasseSWBrand: String, // KASSE_SW_BRAND - Software brand
        val kasseSWVersion: String, // KASSE_SW_VERSION - Software version
        val kasseZertId: String?, // KASSE_ZERT_ID - Certification ID
        val tseSerienNr: String?, // TSE_SERIENNR - TSE serial number
        val tseZertId: String?, // TSE_ZERT_ID - TSE certification ID
    )

    // ============================================
    // CSV Row DTOs - transactions.csv (Bonkopf)
    // ============================================

    /**
     * DTO for transactions.csv row (receipt header/Bonkopf).
     *
     * Represents a single transaction/receipt header in DSFinV-K format.
     * Maps to BillTable data.
     */
    @Serializable
    data class TransactionRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID - Unique receipt ID (UUID)
        val bonNr: String, // BON_NR - Receipt number
        val bonTyp: String, // BON_TYP - Receipt type
        val bonName: String?, // BON_NAME - Receipt name/description
        val storno: Boolean, // STORNO - Is cancelled
        val stornoRef: String?, // STORNO_REF - Reference to cancelled receipt
        val bedienerId: String, // BEDIENER_ID - Operator ID
        val bedienerName: String?, // BEDIENER_NAME - Operator name
        val bonDatum: String, // BON_DATUM - Receipt date
        val bonZeit: String, // BON_ZEIT - Receipt time
        val bonBrutto: String, // BON_BRUTTO - Gross amount
        val bonNetto: String, // BON_NETTO - Net amount
        val bonNotiz: String?, // BON_NOTIZ - Notes
    )

    // ============================================
    // CSV Row DTOs - transactions_tse.csv
    // ============================================

    /**
     * DTO for transactions_tse.csv row (TSE signature data).
     *
     * Represents TSE transaction data linked to receipts.
     * Maps to TseTransactionTable data.
     */
    @Serializable
    data class TransactionTseRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val tseId: String?, // TSE_ID - TSE serial number
        val tseTanr: Long?, // TSE_TANR - TSE transaction number
        val tseTaSig: String?, // TSE_TA_SIG - Transaction signature (Base64)
        val tseTaStart: String?, // TSE_TA_START - Transaction start time
        val tseTaEnde: String?, // TSE_TA_ENDE - Transaction end time
        val tseTaSigNr: Long?, // TSE_TA_SIGNR - Signature counter
        val tseTaVorgangtyp: String?, // TSE_TA_VORGANGSTYP - Process type
        val tseZert: String?, // TSE_ZERT - TSE certificate
        val tseZertI: String?, // TSE_ZERT_I - TSE certificate ID
    )

    // ============================================
    // CSV Row DTOs - lines.csv (Bonpos)
    // ============================================

    /**
     * DTO for lines.csv row (line items/Bonpos).
     *
     * Represents a single line item on a receipt.
     * Maps to OrderItemTable data.
     */
    @Serializable
    data class LineItemRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: Int, // POS_ZEILE - Line number
        val artikelNr: String?, // ARTIKELNR - Article number
        val artikelText: String, // ARTIKELTEXT - Article description
        val menge: String, // MENGE - Quantity
        val mengenEinheit: String, // MENGENEINHEIT - Unit of measure
        val ePPreis: String, // E_PPREIS - Unit price gross
        val gvTyp: String, // GV_TYP - Business case type
        val gvName: String?, // GV_NAME - Business case name
        val inhaus: Boolean, // INHAUS - Dine-in (true) or takeaway (false)
        val storno: Boolean, // STORNO - Is cancelled line
        val stornoRef: String?, // STORNO_REF - Reference to cancelled line
    )

    // ============================================
    // CSV Row DTOs - lines_vat.csv (VAT per line)
    // ============================================

    /**
     * DTO for lines_vat.csv row (VAT details per line item).
     *
     * Represents VAT breakdown for a line item.
     * Maps to BillVatDetailTable and VatTable data.
     */
    @Serializable
    data class LineVatRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: Int, // POS_ZEILE - Line number
        val ustSchluessel: Int, // UST_SCHLUESSEL - VAT key (1=19%, 2=7%, 5=0%)
        val ustSatz: String, // UST_SATZ - VAT rate percentage
        val ustBetrag: String, // UST_BETRAG - VAT amount
        val netto: String, // NETTO - Net amount
        val brutto: String, // BRUTTO - Gross amount
    )

    // ============================================
    // CSV Row DTOs - datapayment.csv
    // ============================================

    /**
     * DTO for datapayment.csv row (individual payments).
     *
     * Represents a single payment transaction.
     * Maps to PaymentTable data.
     */
    @Serializable
    data class DataPaymentRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val zahlungZeile: Int, // ZAHLUNG_ZEILE - Payment line number
        val zahlungsart: String, // ZAHLUNGSART - Payment type
        val waehrung: String, // WAEHRUNG - Currency
        val betrag: String, // BETRAG - Amount
    )

    // ============================================
    // CSV Row DTOs - businesscases.csv
    // ============================================

    /**
     * DTO for businesscases.csv row (business case summaries).
     *
     * Represents aggregated business case data.
     */
    @Serializable
    data class BusinessCaseRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val gvTyp: String, // GV_TYP - Business case type
        val gvName: String?, // GV_NAME - Business case name
        val brutto: String, // BRUTTO - Gross amount
        val netto: String, // NETTO - Net amount
        val ust: String, // UST - VAT amount
    )

    // ============================================
    // CSV Row DTOs - payment.csv (payment summaries)
    // ============================================

    /**
     * DTO for payment.csv row (payment aggregates per Z-Bon).
     *
     * Represents aggregated payment data per Z-report.
     * Maps to ZBonPaymentSummaryTable data.
     */
    @Serializable
    data class PaymentSummaryRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val zahlungsart: String, // ZAHLUNGSART - Payment type
        val waehrung: String, // WAEHRUNG - Currency
        val betrag: String, // BETRAG - Total amount
    )

    // ============================================
    // CSV Row DTOs - cash_per_currency.csv
    // ============================================

    /**
     * DTO for cash_per_currency.csv row (currency totals).
     *
     * Represents cash totals per currency.
     */
    @Serializable
    data class CashPerCurrencyRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val waehrung: String, // WAEHRUNG - Currency
        val betragTyp: String, // BETRAG_TYP - Amount type
        val betrag: String, // BETRAG - Amount
    )

    // ============================================
    // CSV Row DTOs - allocation_groups.csv (Bonkopf_AbrKreis)
    // ============================================

    /**
     * DTO for allocation_groups.csv row (Bonkopf_AbrKreis).
     *
     * Represents the allocation group/table assignment for a transaction.
     * Links transactions to specific tables/service areas for proper accounting.
     * Required by DSFinV-K for transactions that belong to allocation circles.
     *
     * @property zKasseId Cash register ID
     * @property zErstellung Z-report creation timestamp
     * @property zNr Z-report number
     * @property bonId Unique receipt/transaction ID
     * @property abrechnungskreis Allocation circle identifier (table number/session)
     */
    @Serializable
    data class AllocationGroupRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val abrechnungskreis: String, // ABRECHNUNGSKREIS - Table/allocation group ID
    )

    // ============================================
    // CSV Row DTOs - references.csv (Bon_Referenzen)
    // ============================================

    /**
     * DTO for references.csv row (Bon_Referenzen).
     *
     * Represents references between transactions, particularly for:
     * - Storno/cancellation references to original transactions
     * - Item-level cancellation references
     * - Split bill references
     *
     * Required by DSFinV-K for tracking transaction relationships.
     *
     * @property zKasseId Cash register ID
     * @property zErstellung Z-report creation timestamp
     * @property zNr Z-report number
     * @property bonId Current receipt/transaction ID
     * @property posZeile Line number (null for header-level references)
     * @property refTyp Reference type (Transaktion, ExterneRechnung, etc.)
     * @property refName Optional reference name/description
     * @property refDatum Optional reference date
     * @property refZKasseId Referenced cash register ID
     * @property refZNr Referenced Z-report number
     * @property refBonId Referenced receipt ID
     */
    @Serializable
    data class ReferenceRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: String?, // POS_ZEILE - Line number (null for header refs)
        val refTyp: String, // REF_TYP - Reference type
        val refName: String?, // REF_NAME - Reference name
        val refDatum: String?, // REF_DATUM - Reference date
        val refZKasseId: String?, // REF_Z_KASSE_ID - Referenced register
        val refZNr: Long?, // REF_Z_NR - Referenced Z-report number
        val refBonId: String?, // REF_BON_ID - Referenced receipt ID
    )

    // ============================================
    // CSV Row DTOs - itemamounts.csv (Bonpos_Preisfindung)
    // ============================================

    /**
     * DTO for itemamounts.csv row (Bonpos_Preisfindung).
     *
     * Represents price finding/breakdown for line items:
     * - Base price (Basispreis)
     * - Discounts (Rabatt) as negative values
     * - Surcharges (Aufschlag) as positive values
     *
     * Only generated for items with price adjustments (discounts/surcharges).
     *
     * @property zKasseId Cash register ID
     * @property zErstellung Z-report creation timestamp
     * @property zNr Z-report number
     * @property bonId Receipt/transaction ID
     * @property posZeile Line number on the receipt
     * @property typ Price finding type (Basispreis, Rabatt, Aufschlag)
     * @property ustSchluessel VAT key
     * @property pfBrutto Gross amount
     * @property pfNetto Net amount
     * @property pfUst VAT amount
     */
    @Serializable
    data class PriceFindingRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: Int, // POS_ZEILE
        val typ: String, // TYP - Basispreis, Rabatt, Aufschlag
        val ustSchluessel: Int, // UST_SCHLUESSEL - VAT key
        val pfBrutto: String, // PF_BRUTTO - Gross amount
        val pfNetto: String, // PF_NETTO - Net amount
        val pfUst: String, // PF_UST - VAT amount
    )

    // ============================================
    // CSV Row DTOs - subitems.csv (Bonpos_Zusatzinfo)
    // ============================================

    /**
     * DTO for subitems.csv row (Bonpos_Zusatzinfo).
     *
     * Represents sub-items/extras for line items:
     * - Product modifiers/extras (e.g., toppings, add-ons)
     * - Combo components
     * - Item customizations with price impact
     *
     * @property zKasseId Cash register ID
     * @property zErstellung Z-report creation timestamp
     * @property zNr Z-report number
     * @property bonId Receipt/transaction ID
     * @property posZeile Parent line number
     * @property ziArtNr Sub-item article number
     * @property ziGtin Sub-item GTIN/barcode
     * @property ziName Sub-item name/description
     * @property ziWarengrId Product group ID
     * @property ziWarengr Product group name
     * @property ziMenge Quantity
     * @property ziFaktor Factor/multiplier
     * @property ziEinheit Unit of measure
     * @property ziUstSchluessel VAT key
     * @property ziBasispreisBrutto Gross base price
     * @property ziBasispreisNetto Net base price
     * @property ziBasispreisUst VAT amount
     */
    @Serializable
    data class SubItemRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: Int, // POS_ZEILE - Parent line number
        val ziArtNr: String?, // ZI_ART_NR - Article number
        val ziGtin: String?, // ZI_GTIN - GTIN/barcode
        val ziName: String, // ZI_NAME - Name
        val ziWarengrId: String?, // ZI_WARENGR_ID - Product group ID
        val ziWarengr: String?, // ZI_WARENGR - Product group name
        val ziMenge: String, // ZI_MENGE - Quantity
        val ziFaktor: String?, // ZI_FAKTOR - Factor
        val ziEinheit: String?, // ZI_EINHEIT - Unit
        val ziUstSchluessel: Int, // ZI_UST_SCHLUESSEL - VAT key
        val ziBasispreisBrutto: String, // ZI_BASISPREIS_BRUTTO - Gross price
        val ziBasispreisNetto: String, // ZI_BASISPREIS_NETTO - Net price
        val ziBasispreisUst: String, // ZI_BASISPREIS_UST - VAT amount
    )

    // ============================================
    // DSFinV-K Constants and Enums
    // ============================================

    /**
     * DSFinV-K VAT key mapping.
     *
     * Maps German VAT rates to the standard DSFinV-K keys defined in Anlage 2 of the
     * specification:
     * 1 = Allgemeiner Steuersatz (19%), 2 = Ermäßigter Steuersatz (7%),
     * 3 = Durchschnittssatz §24 UStG (10.7%), 4 = Durchschnittssatz §24 UStG (5.5%),
     * 5 = Nicht steuerbare Umsätze (0%), 6 = Umsatzsteuerfrei (0%),
     * 7 = Umsatzsteuer nicht ermittelbar/nicht zuordenbar.
     */
    object VatKeyMapping {
        /** 1 = Allgemeiner Steuersatz — general rate, 19% */
        const val GENERAL = 1

        /** 2 = Ermäßigter Steuersatz — reduced rate, 7% */
        const val REDUCED = 2

        /**
         * 3 = Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG — the CURRENT statutory rate at
         * recording time (7.80 % since 2025; Anlage 2 assigns ID 3 no fixed value).
         * Historical §24-Nr.3 rates map to their own Anlage-2 IDs ([HIST_AVG3_107] etc.,
         * audit STAM-6/7).
         */
        const val AVERAGE_3 = 3

        /** 4 = Durchschnittssatz §24 UStG — average rate, 5.5% */
        const val AVERAGE_1 = 4

        /** 5 = Nicht steuerbare Umsätze — not subject to VAT (0%) */
        const val NICHT_STEUERBAR = 5

        /** 6 = Umsatzsteuerfrei — VAT-exempt turnover (0%) */
        const val UMSATZSTEUERFREI = 6

        /** 7 = Umsatzsteuer nicht ermittelbar/nicht zuordenbar — VAT not determinable/assignable */
        const val NICHT_ERMITTELBAR = 7

        // Historical keys per Anlage 2 (Stand 05.12.2024) — immutable rate assignments
        // (audit STAM-7: historical rates belong to these IDs, never to key 7).
        /** 21 = 16,00 % historischer allgemeiner Steuersatz (07–12/2020 Corona rate). */
        const val HIST_GENERAL_16 = 21

        /** 22 = 5,00 % historischer ermäßigter Steuersatz (07–12/2020 Corona rate). */
        const val HIST_REDUCED_5 = 22

        /** 13 = 10,70 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
        const val HIST_AVG3_107 = 13

        /** 23 = 9,50 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
        const val HIST_AVG3_95 = 23

        /** 33 = 9,00 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
        const val HIST_AVG3_90 = 33

        /** 43 = 8,40 % historischer Durchschnittssatz § 24 Abs. 1 Nr. 3 UStG. */
        const val HIST_AVG3_84 = 43

        /** Tolerance for comparing VAT rates stored with up to 4 decimal places. */
        private const val EPSILON = 0.0001

        private fun isRate(
            vatRate: Double,
            target: Double,
        ) = kotlin.math.abs(vatRate - target) < EPSILON

        /**
         * Gets the VAT key for a given VAT rate percentage.
         *
         * Only the exactly configured German VAT rates map to keys 1-5. A positive rate that
         * does not match any configured rate is deliberately NOT mapped to a 0%-VAT key
         * (5/6) — that would silently drop VAT owed on the transaction. It maps to
         * [NICHT_ERMITTELBAR] (7) instead, so downstream reconciliation can flag it.
         *
         * The rule, and the only one worth remembering: **keys 1–5 carry whatever rate is
         * statutorily current; every superseded rate moves to its own fixed Anlage-2 ID from 11
         * upward.** That is what keeps `stamm_ust` truthful — a line may only ever reference a key
         * whose declared rate really applied.
         *
         * So the §24 Abs. 1 Nr. 3 average rate is key 3 at today's 7,8 %, while 8,4 % (its value
         * until 2025-01-01) is [HIST_AVG3_84] = 43, and the 10,7 % transitional rate is
         * [HIST_AVG3_107] = 13. Neither is unmapped, and neither may be routed to key 3.
         *
         * When a statutory rate changes: move the outgoing rate to its historical ID here, point
         * the current key at the new rate, and update the constant `generateStammUst` declares —
         * all three together, or the declared rate stops matching the referenced key.
         *
         * `VatKeyMappingTest` pins every one of these, including an explicit assertion that 8,4 %
         * is NOT key 3.
         *
         * @param vatRate VAT rate as decimal (e.g., 0.19 for 19%)
         * @return DSFinV-K VAT key
         */
        fun getVatKey(vatRate: Double): Int =
            when {
                isRate(vatRate, 0.19) -> GENERAL
                isRate(vatRate, 0.07) -> REDUCED
                isRate(vatRate, 0.078) -> AVERAGE_3
                isRate(vatRate, 0.055) -> AVERAGE_1
                isRate(vatRate, 0.0) -> NICHT_STEUERBAR
                isRate(vatRate, 0.16) -> HIST_GENERAL_16
                isRate(vatRate, 0.05) -> HIST_REDUCED_5
                isRate(vatRate, 0.107) -> HIST_AVG3_107
                isRate(vatRate, 0.095) -> HIST_AVG3_95
                isRate(vatRate, 0.09) -> HIST_AVG3_90
                isRate(vatRate, 0.084) -> HIST_AVG3_84
                else -> NICHT_ERMITTELBAR
            }

        /**
         * The exact VAT rates (as decimal fractions) that [getVatKey] maps to a real
         * Anlage-2 key (keys 1-5 and the historical 13/21/22/23/33/43) — i.e. everything
         * EXCEPT the [NICHT_ERMITTELBAR] (7) fallback.
         *
         * Single source of truth for the VAT create/update guard (round-3 STAM-4): a rate
         * outside this set would export under UST_SCHLUESSEL 7 with a NON-ZERO computed USt
         * while vat.csv declares key 7 = 0,00 % — an arithmetic contradiction. Keep this set
         * in lockstep with the [getVatKey] when-branches above.
         */
        val MAPPED_RATES: Set<Double> =
            setOf(
                0.0,
                0.05,
                0.055,
                0.07,
                0.078,
                0.084,
                0.09,
                0.095,
                0.107,
                0.16,
                0.19,
            )

        /**
         * True if [vatRate] is a DSFinV-K-mappable rate — it matches a real Anlage-2 key and
         * never falls through to [NICHT_ERMITTELBAR] (7). Uses the same [EPSILON] tolerance as
         * [getVatKey] so float-equality quirks don't reject a legitimately configured rate.
         */
        fun isMappableRate(vatRate: Double): Boolean = MAPPED_RATES.any { isRate(vatRate, it) }
    }

    /** Display names (GV_NAME) shared by the bill-side and Kassenbuch-side DSFinV-K exports. */
    object BusinessCaseNames {
        /**
         * GV_NAME of [BusinessCaseTypes.TRINKGELD_AN] — the ONE label every export site writes, so a
         * closing never lists the same GV_TYP under two names (F54 #2). It is the label the bill-side
         * export has carried since tips first shipped; the Kassenbuch-side tip payout adopted it.
         */
        const val TRINKGELD_AN = "Trinkgeld (AN)"

        /** GV_NAME of [BusinessCaseTypes.TRINKGELD_AG]. */
        const val TRINKGELD_AG = "Trinkgeld (AG)"
    }

    /**
     * DSFinV-K business case types (GV_TYP).
     *
     * Complete enumeration of all DSFinV-K compliant business case types
     * for lines.csv (bonpos) and businesscases.csv export files.
     * Reference: DSFinV-K specification and German fiscal compliance requirements.
     */
    object BusinessCaseTypes {
        // ============================================
        // Standard Business Cases
        // ============================================

        /** Regular sales revenue - default for food, drinks, and retail items */
        const val UMSATZ = "Umsatz"

        /** Discount applied to items (negative amount) */
        const val RABATT = "Rabatt"

        /** Surcharge applied to items (e.g., service charge) */
        const val AUFSCHLAG = "Aufschlag"

        // ============================================
        // Voucher Business Cases (Gutscheine)
        // ============================================

        /** Single-purpose voucher purchase (VAT taxable at time of purchase) */
        const val EINZWECKGUTSCHEIN_KAUF = "EinzweckgutscheinKauf"

        /** Single-purpose voucher redemption (no VAT, already taxed at purchase) */
        const val EINZWECKGUTSCHEIN_EINLOESUNG = "EinzweckgutscheinEinloesung"

        /** Multi-purpose voucher purchase (no VAT, taxed at redemption) */
        const val MEHRZWECKGUTSCHEIN_KAUF = "MehrzweckgutscheinKauf"

        /** Multi-purpose voucher redemption (VAT applies to redeemed items) */
        const val MEHRZWECKGUTSCHEIN_EINLOESUNG = "MehrzweckgutscheinEinloesung"

        // ============================================
        // Tip Business Cases (Trinkgeld)
        // ============================================

        /** Tip for employee (Arbeitnehmer) - passes through to staff */
        const val TRINKGELD_AN = "TrinkgeldAN"

        /** Tip for employer (Arbeitgeber) - retained by business */
        const val TRINKGELD_AG = "TrinkgeldAG"

        // ============================================
        // Deposit Business Cases (Pfand)
        // ============================================

        /** Deposit charged (e.g., bottle deposit) */
        const val PFAND = "Pfand"

        /** Deposit returned */
        const val PFAND_RUECKZAHLUNG = "PfandRueckzahlung"

        // ============================================
        // Receivables Business Cases (Forderungen)
        // ============================================

        /** Receivable creation (credit sale/invoice) */
        const val FORDERUNGSENTSTEHUNG = "Forderungsentstehung"

        /** Receivable resolution (payment received for credit sale) */
        const val FORDERUNGSAUFLOESUNG = "Forderungsaufloesung"

        // ============================================
        // Cash Movement Business Cases
        // ============================================

        /** Private deposit into cash register (owner contribution) */
        const val PRIVATEINLAGE = "Privateinlage"

        /** Private withdrawal from cash register */
        const val PRIVATENTNAHME = "Privatentnahme"

        /** Cash transfer/transit between registers or to bank */
        const val GELDTRANSIT = "Geldtransit"

        /** Cash payout/expense (e.g., business expenses paid in cash) */
        const val AUSZAHLUNG = "Auszahlung"

        /** Opening balance/initial cash in register */
        const val ANFANGSBESTAND = "Anfangsbestand"

        /** Difference between expected and actual cash (Soll/Ist) */
        const val DIFFERENZ_SOLL_IST = "DifferenzSollIst"

        // ============================================
        // Additional DSFinV-K Business Cases (per spec v2.3+)
        // ============================================

        /** Deposit/prepayment received (Anzahlungseinstellung) */
        const val ANZAHLUNGSEINSTELLUNG = "Anzahlungseinstellung"

        /** Deposit/prepayment applied/resolved (Anzahlungsaufloesung) */
        const val ANZAHLUNGSAUFLOESUNG = "Anzahlungsaufloesung"

        /** True subsidy - third party payment that reduces customer price */
        const val ZUSCHUSS_ECHT = "ZuschussEcht"

        /** False subsidy - price reduction treated as discount */
        const val ZUSCHUSS_UNECHT = "ZuschussUnecht"

        /** Wage/salary payment from cash register */
        const val LOHNZAHLUNG = "Lohnzahlung"

        /** Cash deposit into register (general deposit, not owner-specific) */
        const val EINZAHLUNG = "Einzahlung"
    }

    /**
     * DSFinV-K receipt types (BON_TYP).
     *
     * Complete enumeration of all DSFinV-K compliant receipt types
     * per specification Anhang B BON_TYP (Vorgangstyp).
     *
     * Note: Only "Beleg" allows all payment types. All AV* types (except AVTraining)
     * only allow payment type "Keine" (none).
     *
     * @see org.example.project.core.domain.enums.tse.BonType
     */
    object ReceiptTypes {
        /** Standard receipt - regular sale with payment (all payment types allowed) */
        const val BELEG = "Beleg"

        /** Invoice without immediate cash movement (Zahlart: Keine) */
        const val AV_RECHNUNG = "AVRechnung"

        /** Internal transfer between registers/locations (Zahlart: Keine) */
        const val AV_TRANSFER = "AVTransfer"

        /** Order placed without payment - e.g., table order before checkout (Zahlart: Keine) */
        const val AV_BESTELLUNG = "AVBestellung"

        /** Training mode receipt - not fiscally relevant (all payment types allowed) */
        const val AV_TRAINING = "AVTraining"

        /** Storno/cancellation of a previous transaction (Zahlart: Keine) */
        const val AV_BELEGSTORNO = "AVBelegstorno"

        /** Aborted/cancelled receipt - transaction started but not completed (Zahlart: Keine) */
        const val AV_BELEGABBRUCH = "AVBelegabbruch"

        /** Employee benefits / Sachbezug transaction (Zahlart: Keine) */
        const val AV_SACHBEZUG = "AVSachbezug"

        /** Other/miscellaneous transaction type (Zahlart: Keine) */
        const val AV_SONSTIGE = "AVSonstige"
    }

    /**
     * DSFinV-K reference types (REF_TYP) for references.csv.
     *
     * Types of references between transactions.
     */
    object ReferenceTypes {
        /** Reference to another transaction in the same system */
        const val TRANSAKTION = "Transaktion"

        /** Reference to an external invoice/document */
        const val EXTERNE_RECHNUNG = "ExterneRechnung"

        /** Reference to an external delivery note */
        const val EXTERNER_LIEFERSCHEIN = "ExternerLieferschein"

        /** General/other external reference type */
        const val EXTERNE_SONSTIGE = "ExterneSonstige"
    }

    /**
     * DSFinV-K price finding types (TYP) for itemamounts.csv.
     *
     * Types of price components in price breakdown.
     */
    object PriceFindingTypes {
        // Anhang E p. 98 closes the TYP domain to exactly: "base_amount" bzw. "Grundpreis",
        // "discount" bzw. "Rabatt", "extra_amount" bzw. "Zuschlag" (audit H12 — the old
        // "Basispreis"/"Aufschlag" literals were outside the domain).
        /** Base/original price before adjustments */
        const val BASISPREIS = "Grundpreis"

        /** Discount (negative amount) */
        const val RABATT = "Rabatt"

        /** Surcharge (positive amount) */
        const val AUFSCHLAG = "Zuschlag"
    }

    // ============================================
    // CSV Row DTOs - Bonkopf_USt (bonkopf_ust.csv)
    // ============================================

    /**
     * DTO for bonkopf_ust.csv row (VAT per receipt header).
     *
     * Per DSFinV-K spec 3.1.2.1: VAT breakdown per receipt/transaction.
     * Contains the amounts printed on the receipt separated by VAT rate.
     */
    @Serializable
    data class BonkopfUstRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val ustSchluessel: Int, // UST_SCHLUESSEL - VAT key
        val bonBrutto: String, // BON_BRUTTO - Gross amount
        val bonNetto: String, // BON_NETTO - Net amount
        val bonUst: String, // BON_UST - VAT amount
    )

    // ============================================
    // CSV Row DTOs - Stamm_Abschluss (stamm_abschluss.csv)
    // ============================================

    /**
     * DTO for stamm_abschluss.csv row (closing master data).
     *
     * Per DSFinV-K spec 3.2.1: Cash closing data with company information.
     */
    @Serializable
    data class StammAbschlussRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val zBuchungstag: String, // Z_BUCHUNGSTAG - Booking date
        val taxonomieVersion: String, // TAXONOMIE_VERSION - DSFinV-K version
        val zStartId: String, // Z_START_ID - First BON_ID
        val zEndeId: String, // Z_ENDE_ID - Last BON_ID
        val name: String, // NAME - Company name
        val strasse: String, // STRASSE - Street
        val plz: String, // PLZ - Postal code
        val ort: String, // ORT - City
        val land: String, // LAND - Country
        val stNr: String?, // STNR - Tax number
        val ustId: String?, // USTID - VAT ID
        val zSeZahlungen: String, // Z_SE_ZAHLUNGEN - Total payments
        val zSeBarzahlungen: String, // Z_SE_BARZAHLUNGEN - Total cash payments
    )

    // ============================================
    // CSV Row DTOs - Stamm_Orte (stamm_orte.csv)
    // ============================================

    /**
     * DTO for stamm_orte.csv row (location master data).
     *
     * Per DSFinV-K spec 3.2.2: Business location information.
     */
    @Serializable
    data class StammOrteRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val locName: String, // LOC_NAME - Location name
        val locStrasse: String, // LOC_STRASSE - Street
        val locPlz: String, // LOC_PLZ - Postal code
        val locOrt: String, // LOC_ORT - City
        val locLand: String, // LOC_LAND - Country
        val locUstId: String?, // LOC_USTID - Location VAT ID
    )

    // ============================================
    // CSV Row DTOs - Stamm_Kassen (stamm_kassen.csv)
    // ============================================

    /**
     * DTO for stamm_kassen.csv row (cash register master data).
     *
     * Per DSFinV-K spec 3.2.3: Cash register/POS information.
     * Note: Replaces the old CashRegisterRowDto with correct field names.
     */
    @Serializable
    data class StammKassenRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val kasseBrand: String, // KASSE_BRAND - Manufacturer
        val kasseModell: String, // KASSE_MODELL - Model
        val kasseSerienNr: String, // KASSE_SERIENNR - Serial number
        val kasseSwBrand: String, // KASSE_SW_BRAND - Software brand
        val kasseSwVersion: String, // KASSE_SW_VERSION - Software version
        val kasseBasiswaehCode: String, // KASSE_BASISWAEH_CODE - Base currency
        val keineUstZuordnung: String?, // KEINE_UST_ZUORDNUNG - VAT not determinable
    )

    // ============================================
    // CSV Row DTOs - Stamm_USt (stamm_ust.csv / vat.csv)
    // ============================================

    /**
     * DTO for stamm_ust.csv row (VAT rate master data).
     *
     * Per DSFinV-K spec 3.2.6: VAT rate definitions.
     */
    @Serializable
    data class StammUstRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val ustSchluessel: Int, // UST_SCHLUESSEL - VAT key
        val ustSatz: String, // UST_SATZ - VAT rate percentage
        val ustBeschr: String, // UST_BESCHR - Description
    )

    // ============================================
    // CSV Row DTOs - Stamm_TSE (stamm_tse.csv)
    // ============================================

    /**
     * DTO for stamm_tse.csv row (TSE master data).
     *
     * Per DSFinV-K spec 3.2.7: TSE device information.
     */
    @Serializable
    data class StammTseRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val tseId: Int, // TSE_ID - TSE identifier for this closing
        val tseSerial: String, // TSE_SERIAL - Serial number (hash of public key)
        val tseSigAlgo: String, // TSE_SIG_ALGO - Signature algorithm
        val tseZeitformat: String, // TSE_ZEITFORMAT - Time format
        val tsePdEncoding: String, // TSE_PD_ENCODING - ProcessData encoding
        val tsePublicKey: String?, // TSE_PUBLIC_KEY - Public key (base64)
        // TSE_ZERTIFIKAT_I, II, III, IV, V … — the certificate in ≤1000-char base64 chunks
        // (Anhang E p. 78-79). Part 0 → TSE_ZERTIFIKAT_I; as many parts as the certificate needs,
        // never truncated. Empty when the device stored no certificate.
        val tseZertifikatParts: List<String>,
    )

    // ============================================
    // CSV Row DTOs - Stamm_Terminals (stamm_terminals.csv)
    // ============================================

    /**
     * DTO for stamm_terminals.csv row (Terminal master data).
     *
     * Per DSFinV-K spec: Terminal information for multi-client POS systems.
     * Each terminal represents a client app instance connecting to the server.
     *
     * @property zKasseId Cash register ID (server instance)
     * @property zErstellung Z-report creation timestamp
     * @property zNr Z-report number
     * @property terminalId Human-readable terminal ID (e.g., "T001")
     * @property terminalBrand Terminal brand/platform (Android, Windows, etc.)
     * @property terminalModel Terminal model
     * @property terminalSerialNr Terminal serial number
     * @property terminalSwBrand Terminal software brand
     * @property terminalSwVersion Terminal software version
     */
    @Serializable
    data class StammTerminalsRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val terminalId: String, // TERMINAL_ID
        val terminalBrand: String?, // TERMINAL_BRAND
        val terminalModel: String?, // TERMINAL_MODEL
        val terminalSerialNr: String?, // TERMINAL_SERIAL_NR
        val terminalSwBrand: String, // TERMINAL_SW_BRAND
        val terminalSwVersion: String?, // TERMINAL_SW_VERSION
    )

    // ============================================
    // CSV Row DTOs - Stamm_Agenturen (pa.csv)
    // ============================================

    /**
     * DTO for pa.csv row (Stamm_Agenturen / agency master data).
     *
     * Per DSFinV-K spec 3.2.5: Agency information for third-party transactions.
     * Currently empty — no agencies are used in this system.
     */
    @Serializable
    data class StammAgenturenRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val agenturId: Int, // AGENTUR_ID
        val agenturName: String, // AGENTUR_NAME
        val agenturStrasse: String?, // AGENTUR_STRASSE
        val agenturPlz: String?, // AGENTUR_PLZ
        val agenturOrt: String?, // AGENTUR_ORT
        val agenturLand: String?, // AGENTUR_LAND
        val agenturStNr: String?, // AGENTUR_STNR
        val agenturUstId: String?, // AGENTUR_USTID
    )

    // ============================================
    // Updated CSV Row DTOs with correct DSFinV-K field names
    // ============================================

    /**
     * DTO for lines.csv row (Bonpos) with DSFinV-K 2.4 compliant field names.
     *
     * Updated to include all required fields per spec 3.1.1.
     */
    @Serializable
    data class BonposRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: String, // POS_ZEILE - Line number (String per spec)
        val gutscheinNr: String?, // GUTSCHEIN_NR - Voucher number
        val artikeltext: String, // ARTIKELTEXT - Article text
        val posTerminalId: String?, // POS_TERMINAL_ID - Terminal ID
        val gvTyp: String, // GV_TYP - Business case type
        val gvName: String?, // GV_NAME - Business case name
        val inhaus: String, // INHAUS - Dine-in flag (1/0)
        val pStorno: String, // P_STORNO - Position storno flag (1/0)
        val agenturId: Int?, // AGENTUR_ID - Agency ID
        val artNr: String?, // ART_NR - Article number
        val gtin: String?, // GTIN - Global Trade Item Number
        val warengrId: String?, // WARENGR_ID - Product group ID
        val warengr: String?, // WARENGR - Product group name
        val menge: String, // MENGE - Quantity
        val faktor: String?, // FAKTOR - Factor (e.g., package size)
        val einheit: String, // EINHEIT - Unit of measure
        val stkBr: String, // STK_BR - Unit price gross
    )

    /**
     * DTO for bonpos_ust.csv row with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.1.1.1.
     */
    @Serializable
    data class BonposUstRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val posZeile: String, // POS_ZEILE
        val ustSchluessel: Int, // UST_SCHLUESSEL
        val posBrutto: String, // POS_BRUTTO - Gross amount
        val posNetto: String, // POS_NETTO - Net amount
        val posUst: String, // POS_UST - VAT amount
    )

    /**
     * DTO for transactions.csv row (Bonkopf) with DSFinV-K 2.4 compliant field names.
     *
     * Updated to include all required fields per spec 3.1.2.
     */
    @Serializable
    data class BonkopfRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val bonNr: Long, // BON_NR - Receipt number (Numeric per spec)
        val bonTyp: String, // BON_TYP - Receipt type
        val bonName: String?, // BON_NAME - Receipt description
        val terminalId: String?, // TERMINAL_ID - Terminal ID
        val bonStorno: String, // BON_STORNO - Storno flag (1/0)
        val bonStart: String?, // BON_START - Transaction start time
        val bonEnde: String, // BON_ENDE - Transaction end time
        val bedienerId: String, // BEDIENER_ID - Operator ID
        val bedienerName: String?, // BEDIENER_NAME - Operator name
        val umsBrutto: String, // UMS_BRUTTO - Gross turnover
        val kundeName: String?, // KUNDE_NAME - Customer name
        val kundeId: String?, // KUNDE_ID - Customer ID
        val kundeTyp: String?, // KUNDE_TYP - Customer type
        val kundeStrasse: String?, // KUNDE_STRASSE - Customer street
        val kundePlz: String?, // KUNDE_PLZ - Customer postal code
        val kundeOrt: String?, // KUNDE_ORT - Customer city
        val kundeLand: String?, // KUNDE_LAND - Customer country
        val kundeUstId: String?, // KUNDE_USTID - Customer VAT ID
        val bonNotiz: String?, // BON_NOTIZ - Notes
    )

    /**
     * DTO for datapayment.csv row (Bonkopf_Zahlarten) with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.1.2.3.
     */
    @Serializable
    data class BonkopfZahlartenRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val zahlartTyp: String, // ZAHLART_TYP - Payment type
        val zahlartName: String?, // ZAHLART_NAME - Payment name
        // ZAHLWAEH_* only for FOREIGN currency (Anhang E p. 91: „wenn die verwendete
        // Währung von der Basiswährung abweicht") — empty for EUR (audit BKOPF-8).
        val zahlwaehCode: String?, // ZAHLWAEH_CODE - Currency code (foreign only)
        val zahlwaehBetrag: String?, // ZAHLWAEH_BETRAG - Amount in foreign currency
        val basiswaehBetrag: String, // BASISWAEH_BETRAG - Amount in base currency
    )

    /**
     * DTO for transactions_tse.csv row (TSE_Transaktionen) with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.1.2.5.
     */
    @Serializable
    data class TseTransaktionenRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val bonId: String, // BON_ID
        val tseId: Int?, // TSE_ID - TSE identifier (null = no device used)
        val tseTanr: Long?, // TSE_TANR - Transaction number (null = unsigned)
        val tseTaStart: String?, // TSE_TA_START - Transaction start time
        val tseTaEnde: String?, // TSE_TA_ENDE - Transaction end time
        val tseTaVorgangsart: String?, // TSE_TA_VORGANGSART - Process type
        val tseTaSigz: Long?, // TSE_TA_SIGZ - Signature counter
        val tseTaSig: String?, // TSE_TA_SIG - Signature
        val tseTaFehler: String?, // TSE_TA_FEHLER - Error information
        val tseVorgangsdaten: String?, // TSE_VORGANGSDATEN - Process data (optional; intentionally
        // always emitted null/empty — see TransactionsTseCsvExporter)
    )

    /**
     * DTO for businesscases.csv row (Z_GV_Typ) with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.3.1.
     */
    @Serializable
    data class ZGvTypRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val gvTyp: String, // GV_TYP
        val gvName: String?, // GV_NAME
        val agenturId: Int?, // AGENTUR_ID
        val ustSchluessel: Int, // UST_SCHLUESSEL
        val zUmsBrutto: String, // Z_UMS_BRUTTO
        val zUmsNetto: String, // Z_UMS_NETTO
        val zUst: String, // Z_UST
    )

    /**
     * DTO for payment.csv row (Z_Zahlart) with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.3.2.
     */
    @Serializable
    data class ZZahlartRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val zahlartTyp: String, // ZAHLART_TYP
        val zahlartName: String?, // ZAHLART_NAME
        val zZahlartBetrag: String, // Z_ZAHLART_BETRAG
    )

    /**
     * DTO for cash_per_currency.csv row (Z_Waehrungen) with DSFinV-K 2.4 compliant field names.
     *
     * Updated per spec 3.3.3.
     */
    @Serializable
    data class ZWaehrungenRowDto(
        val zKasseId: String, // Z_KASSE_ID
        val zErstellung: String, // Z_ERSTELLUNG
        val zNr: Long, // Z_NR
        val zahlartWaeh: String, // ZAHLART_WAEH - Currency
        val zahlartBetragWaeh: String, // ZAHLART_BETRAG_WAEH - Amount
    )

    // ============================================
    // DSFinV-K Payment Types (ZAHLART_TYP)
    // ============================================

    /**
     * DSFinV-K payment types per Anhang D.
     */
    object PaymentTypes {
        const val BAR = "Bar"
        const val UNBAR = "Unbar"
        const val KEINE = "Keine"
        const val EC_KARTE = "ECKarte"
        const val KREDITKARTE = "Kreditkarte"
        const val EL_ZAHLUNGSDIENSTLEISTER = "ElZahlungsdienstleister"
        const val GUTHABENKARTE = "Guthabenkarte"
    }
}
