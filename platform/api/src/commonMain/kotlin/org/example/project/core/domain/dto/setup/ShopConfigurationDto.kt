package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VoucherConfig

/**
 * Container object for all ShopConfiguration-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and updates
 * to maintain clear separation of concerns and consistent API structure.
 */
object ShopConfigurationDto {
    // ===== VAT MAPPING CONFIGURATION DTOS =====

    /**
     * Request DTO for updating VAT mapping configuration.
     *
     * Contains type-safe VAT mapping structure that defines
     * which VAT class to apply for each product type based on conditions.
     */
    @Serializable
    data class UpdateVatMappingRequestDto(
        /** Type-safe VAT mapping configuration */
        val vatMapping: VatMappingConfig,
    ) {
        /**
         * Validates the VAT mapping configuration.
         *
         * Ensures all VAT class references are valid (A, B, or C)
         * and all required product types are present.
         */
        fun validate() {
            require(vatMapping.isValid()) {
                "Invalid VAT mapping: all VAT classes must be A, B, or C"
            }
        }
    }

    /**
     * Response DTO for VAT mapping operations.
     *
     * Returns the current VAT mapping configuration with
     * validation status and human-readable description.
     */
    @Serializable
    data class VatMappingResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Type-safe VAT mapping configuration */
        val vatMapping: VatMappingConfig,
        /** Whether the configuration is valid */
        val isValid: Boolean,
        /** Human-readable description of the VAT rules */
        val description: String,
    )

    // ===== VOUCHER CONFIGURATION DTOS =====

    /**
     * Request DTO for updating voucher configuration.
     *
     * Contains type-safe voucher configuration structure that defines
     * supported voucher sources, validation rules, and global settings.
     */
    @Serializable
    data class UpdateVoucherConfigRequestDto(
        /** Type-safe voucher configuration */
        val voucherConfig: VoucherConfig,
    ) {
        /**
         * Validates the voucher configuration.
         *
         * Ensures all voucher sources are properly configured,
         * validation rules are valid, and global settings are within acceptable ranges.
         */
        fun validate() {
            require(voucherConfig.isValid()) {
                "Invalid voucher configuration: check source configurations and validation rules"
            }
        }
    }

    /**
     * Response DTO for voucher configuration operations.
     *
     * Returns the current voucher configuration with
     * validation status and human-readable description.
     */
    @Serializable
    data class VoucherConfigResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Type-safe voucher configuration */
        val voucherConfig: VoucherConfig,
        /** Whether the configuration is valid */
        val isValid: Boolean,
        /** Summary of enabled voucher sources */
        val enabledSources: List<String>,
    )

    // ===== PRINT SETTINGS CONFIGURATION DTOS =====

    /**
     * Request DTO for updating print settings configuration.
     *
     * Contains the enabled/disabled state for printer configuration.
     */
    @Serializable
    data class UpdatePrintSettingsRequestDto(
        /** Whether print settings are enabled */
        val enabled: Boolean,
    )

    /**
     * Response DTO for print settings operations.
     *
     * Returns the current print settings configuration.
     */
    @Serializable
    data class PrintSettingsResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Whether print settings are enabled */
        val enabled: Boolean,
    )

    // ===== DATEV XML SCHNITTSTELLE CONFIGURATION DTOS =====

    /**
     * Request DTO for updating DATEV XML Schnittstelle configuration.
     *
     * Contains settings for DATEV XML export: cash account, name,
     * chart of accounts, and server-side export storage path.
     */
    @Serializable
    data class UpdateDatevXmlConfigRequestDto(
        /** Cash register name (e.g., "Kasse") */
        val kassenName: String,
        /** Server-side export storage path (empty = no server copy) */
        val exportPath: String = "",
        /** DATEV Beraternummer (consultant number) */
        val consultantNumber: String = "",
        /** DATEV Mandantennummer (client number) */
        val clientNumber: String = "",
        /**
         * DATEV Debitoren-Sammeldebitor (Personenkonto) for the Ausgangsrechnungen export (slice 4d).
         * Persisted server-side as its own flat `DATEV_AR_SAMMELDEBITOR` setting, not inside the
         * `DATEV_XML_CONFIG` blob — so it is deliberately absent from [DatevXmlConfig].
         */
        val sammeldebitor: String = "",
        /** AP export mode: "MINIMAL" or "BEST_EFFORT" (default) */
        val apExportMode: String = "BEST_EFFORT",
        /** Buchungsstapel: Wirtschaftsjahr-Beginn as ISO "YYYY-MM-DD" (empty = use current year). */
        val wirtschaftsjahrBeginn: String = "",
        /** Buchungsstapel: Sachkontenlänge, 4..8 (default 4). */
        val sachkontenlaenge: Int = 4,
        /** Buchungsstapel: Lock bookings on import (Festschreibung). */
        val festschreibung: Boolean = false,
        /** Master toggle for automatic DATEV exports. */
        val enabled: Boolean = false,
        /** Auto cadence for Kassenbuch DATEV XML exports. */
        val kassenbuchCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Auto cadence for Eingangsrechnungen (AP-ledger) DATEV XML exports. */
        val apLedgerCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Auto cadence for Buchungsstapel CSV exports. */
        val buchungsstapelCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Hour-of-day (0..23) at which the scheduler triggers automatic exports. */
        val runHour: Int = 4,
    ) {
        /**
         * Validates the DATEV XML configuration.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(kassenName.isNotBlank()) { "Kassen-Name must not be blank" }
            require(apExportMode in listOf("MINIMAL", "BEST_EFFORT")) {
                "apExportMode must be MINIMAL or BEST_EFFORT"
            }
            require(sachkontenlaenge in 4..8) {
                "sachkontenlaenge must be between 4 and 8"
            }
            require(runHour in 0..23) { "runHour must be 0..23" }
        }
    }

    /**
     * Stored shape of the `DATEV_XML_CONFIG` setting.
     *
     * Exists so the write path can serialize instead of interpolating operator free text into a
     * JSON template — `kassenName`, `exportPath`, `consultantNumber`, `clientNumber` and
     * `wirtschaftsjahrBeginn` all come from a form, and a `"` in any of them used to end the JSON
     * string literal early and rewrite the whole blob.
     *
     * **No property may be given a default.** `Json` encodes with `encodeDefaults = false`, so a
     * defaulted property whose value happened to equal that default would be dropped from the
     * stored object and the reader in `ShopConfigurationManager` would silently fall back.
     */
    @Serializable
    data class DatevXmlConfig(
        val enabled: Boolean,
        val kassenName: String,
        val exportPath: String,
        val consultantNumber: String,
        val clientNumber: String,
        val apExportMode: String,
        val wirtschaftsjahrBeginn: String,
        val sachkontenlaenge: Int,
        val festschreibung: Boolean,
        val kassenbuchCadence: DatevAutoCadence,
        val apLedgerCadence: DatevAutoCadence,
        val buchungsstapelCadence: DatevAutoCadence,
        val runHour: Int,
    )

    /**
     * Response DTO for DATEV XML Schnittstelle configuration operations.
     *
     * Returns the current DATEV XML export settings for a shop.
     */
    @Serializable
    data class DatevXmlConfigResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Cash register name */
        val kassenName: String,
        /** Server-side export storage path */
        val exportPath: String,
        /** DATEV Beraternummer (consultant number) */
        val consultantNumber: String = "",
        /** DATEV Mandantennummer (client number) */
        val clientNumber: String = "",
        /** DATEV Debitoren-Sammeldebitor (Personenkonto) for the Ausgangsrechnungen export (slice 4d). */
        val sammeldebitor: String = "",
        /** AP export mode: "MINIMAL" or "BEST_EFFORT" */
        val apExportMode: String = "BEST_EFFORT",
        /** Buchungsstapel: Wirtschaftsjahr-Beginn as ISO "YYYY-MM-DD" (empty = use current year). */
        val wirtschaftsjahrBeginn: String = "",
        /** Buchungsstapel: Sachkontenlänge, 4..8. */
        val sachkontenlaenge: Int = 4,
        /** Buchungsstapel: Lock bookings on import (Festschreibung). */
        val festschreibung: Boolean = false,
        /** Master toggle for automatic DATEV exports. */
        val enabled: Boolean = false,
        /** Auto cadence for Kassenbuch DATEV XML exports. */
        val kassenbuchCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Auto cadence for Eingangsrechnungen (AP-ledger) DATEV XML exports. */
        val apLedgerCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Auto cadence for Buchungsstapel CSV exports. */
        val buchungsstapelCadence: DatevAutoCadence = DatevAutoCadence.OFF,
        /** Hour-of-day (0..23) at which the scheduler triggers automatic exports. */
        val runHour: Int = 4,
    )

    // ===== CASH-BOOK TRANSFER TARGET DTOS =====

    /**
     * Response for GET /config/{shopId}/cash-book-transfer-target.
     *
     * [cashBookId] `null` means the automatic Geldtransit is **off**: no Z-Bon moves the day's
     * Tageseinnahme out of the fiscal Ladenkasse. That is a real, valid configuration and the
     * server's default, not a missing value — see [UpdateCashBookTransferTargetRequestDto] for how
     * the request keeps "off" distinct from "not sent".
     */
    @Serializable
    data class CashBookTransferTargetResponseDto(
        val shopId: Long,
        val cashBookId: Long? = null,
    )

    /**
     * Request for PUT /config/{shopId}/cash-book-transfer-target.
     *
     * [cashBookId] is nullable **and carries no default on purpose**. Switching the automatic
     * transfer off is an explicit `{"cashBookId": null}`; a body that omits the field entirely is
     * rejected as a missing field rather than silently read as "off". Without that, a caller that
     * forgot to serialize the picker's value would quietly disable the Geldtransit for the whole
     * shop and nothing would say so.
     */
    @Serializable
    data class UpdateCashBookTransferTargetRequestDto(
        val cashBookId: Long?,
    )

    /**
     * Response for GET/PUT /config/{shopId}/kassenbuch-beleg-age.
     *
     * [maxAgeDays] is how many days back a new Kassenbuch entry's Beleg (receipt) date may lie —
     * the shop's setting, or `KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS` when unset. Clients
     * apply the same bound in their entry forms.
     */
    @Serializable
    data class KassenbuchBelegAgeResponseDto(
        val shopId: Long,
        val maxAgeDays: Int,
    )

    /**
     * Request for PUT /config/{shopId}/kassenbuch-beleg-age. [maxAgeDays] must lie in
     * `KassenbuchBelegDateRule.ALLOWED_MAX_AGE_DAYS`; no default, so a body without it is rejected.
     */
    @Serializable
    data class UpdateKassenbuchBelegAgeRequestDto(
        val maxAgeDays: Int,
    )

    // ===== ORDER RULES CONFIGURATION DTOS =====

    /**
     * Request DTO for updating order rules configuration.
     */
    @Serializable
    data class UpdateOrderRulesRequestDto(
        /** Whether employee selection is required when opening a free table */
        val employeeSelectionOnFreeTable: Boolean,
        /** Whether the round-robin auto-assignment rule is active */
        val roundRobinAssignment: Boolean = false,
    ) {
        init {
            require(!(employeeSelectionOnFreeTable && roundRobinAssignment)) {
                "employeeSelectionOnFreeTable and roundRobinAssignment are mutually exclusive"
            }
        }
    }

    /**
     * Response DTO for order rules configuration operations.
     */
    @Serializable
    data class OrderRulesResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Whether employee selection is required when opening a free table */
        val employeeSelectionOnFreeTable: Boolean,
        /** Whether the round-robin auto-assignment rule is active */
        val roundRobinAssignment: Boolean = false,
    )

    // ===== BILLING RULES CONFIGURATION DTOS =====

    /**
     * Request DTO for updating billing rules configuration.
     */
    @Serializable
    data class UpdateBillingRulesRequestDto(
        /** Whether any cashier can create bills for any table session */
        val allowCrossEmployeeBilling: Boolean,
    )

    /**
     * Response DTO for billing rules configuration operations.
     */
    @Serializable
    data class BillingRulesResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** Whether any cashier can create bills for any table session */
        val allowCrossEmployeeBilling: Boolean,
    )

    // ===== BREAK RULES CONFIGURATION DTOS =====

    @Serializable
    data class BreakRuleEntry(
        val thresholdMinutes: Int,
        val breakMinutes: Int,
        val repeating: Boolean = false,
    )

    @Serializable
    data class BreakRulesConfig(
        val rules: List<BreakRuleEntry> = emptyList(),
    )

    @Serializable
    data class BreakRulesConfigResponseDto(
        val shopId: Long,
        val rules: List<BreakRuleEntry>,
    )

    // ===== PDF STORAGE CONFIGURATION DTOS =====

    /**
     * Request DTO for updating PDF storage configuration.
     *
     * Contains settings for PDF document storage including path, file size limits,
     * and additional custom document types beyond the default [DocumentType] enum.
     */
    @Serializable
    data class UpdateDocumentStorageConfigRequestDto(
        /** PDF storage root path (empty string means use OS default) */
        val path: String,
        /** Maximum upload file size in MB */
        val maxUploadSizeMb: Int,
        /** Additional custom document types (folder names) beyond the default enum types */
        val additionalDocumentTypes: List<String> = emptyList(),
    ) {
        /**
         * Validates the PDF storage configuration.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(maxUploadSizeMb > 0) { "Maximum upload size must be greater than 0" }
            require(maxUploadSizeMb <= 100) { "Maximum upload size must not exceed 100 MB" }
            additionalDocumentTypes.forEach { type ->
                require(type.isNotBlank()) { "Document type name cannot be blank" }
                require(type.length <= 50) { "Document type name must not exceed 50 characters" }
                require(!type.contains("/") && !type.contains("\\")) {
                    "Document type name must not contain path separators"
                }
            }
        }
    }

    /**
     * Stored shape of the `DOCUMENT_STORAGE_CONFIG` setting.
     *
     * `path` and every entry of `additionalDocumentTypes` are operator free text — the latter is
     * only screened for blanks, length and path separators, so a `"` passes validation. Both were
     * previously interpolated into a JSON template. See [DatevXmlConfig] on why no property here
     * may carry a default.
     */
    @Serializable
    data class DocumentStorageConfig(
        val path: String,
        val maxUploadSizeMb: Int,
        val additionalDocumentTypes: List<String>,
    )

    /**
     * Response DTO for PDF storage configuration operations.
     *
     * Returns the current PDF storage settings for a shop, including both
     * the default document types from the enum and any additional custom types.
     */
    @Serializable
    data class DocumentStorageConfigResponseDto(
        /** Shop ID this configuration belongs to */
        val shopId: Long,
        /** PDF storage root path (effective path, resolved from config or OS default) */
        val path: String,
        /** Maximum upload file size in MB */
        val maxUploadSizeMb: Int,
        /** Default document types from DocumentType enum (read-only) */
        val defaultDocumentTypes: List<String>,
        /** Additional custom document types configured by the user */
        val additionalDocumentTypes: List<String>,
    )

    // ===== KASSENMELDUNG CONFIGURATION DTOS =====

    /**
     * Internal config structure stored in KASSEN_MELDUNG_CONFIG.
     */
    @Serializable
    data class KassenMeldungConfigDto(
        val kasseSeriennr: String = "",
        val kasseBrand: String = "",
        val kasseModell: String = "Desktop/Server POS",
        val kasseSwBrand: String = "",
        val kasseSwVersion: String = "",
        val artDesAufzeichnungssystems: String = "Computergestützte/PC-Kassensysteme",
        val inbetriebnahme: String = "",
    )

    /**
     * Aggregated Kassenmeldung response for ELSTER compliance.
     * Combines shop info, eAS config, and live TSE data.
     */
    @Serializable
    data class KassenMeldungResponseDto(
        // Betriebsstätte
        val betriebsstaetteName: String,
        val betriebsstaetteStreet: String,
        val betriebsstaetteZip: String,
        val betriebsstaetteCity: String,
        val betriebsstaetteCountry: String,
        // Elektronisches Aufzeichnungssystem (eAS)
        val kasseSeriennr: String,
        val kasseBrand: String,
        val kasseModell: String,
        val kasseSwBrand: String,
        val kasseSwVersion: String,
        val artDesAufzeichnungssystems: String,
        val anschaffung: String? = null,
        val inbetriebnahme: String,
        // TSE (live from TSE API + config)
        val tseSerialNumberHex: String? = null,
        val tseDescription: String? = null,
        val tseCertificateExpirationDate: Long? = null,
        val tseFormFactor: String? = null,
        val tseBsiCertId: String? = null,
    )

    // ===== BROWSER STATIONS =====

    /** The roles of terminal that may run as a browser station in this shop. */
    @Serializable
    data class BrowserStationsResponseDto(
        val shopId: Long,
        val roles: List<String>,
    )

    @Serializable
    data class UpdateBrowserStationsRequestDto(
        val roles: List<String>,
    )

    // ===== WEB ACCESS =====

    /** Server-wide web settings: the API-docs switch and the default page for `/`. */
    @Serializable
    data class WebAccessResponseDto(
        val shopId: Long,
        /** What is served right now (setting, server override and dev mode combined). */
        val apiDocsEnabled: Boolean,
        /** The stored Web-Admin switch. */
        val apiDocsSetting: Boolean,
        /** True when `POS_API_DOCS` forces the state — the switch is read-only then. */
        val apiDocsLockedByServerConfig: Boolean,
        /** `none` | `admin` | `pos` — [org.example.project.core.domain.enums.setup.StartPageTarget.wire]. */
        val startPageDefault: String,
        /** Targets that may be chosen right now (`pos` only while a browser-station role is enabled). */
        val availableStartPageTargets: List<String>,
    )

    @Serializable
    data class UpdateWebAccessRequestDto(
        val apiDocsEnabled: Boolean,
        val startPageDefault: String,
    )
}
