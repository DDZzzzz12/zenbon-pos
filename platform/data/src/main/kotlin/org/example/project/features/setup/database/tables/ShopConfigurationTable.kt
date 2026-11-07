/**
 * Database table definition for shop configuration settings using key-value pattern.
 *
 * Stores flexible configuration settings as key-value pairs, allowing dynamic
 * settings without schema migrations. Each shop can have multiple settings,
 * with type information stored alongside values for proper parsing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.setup.database.tables

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Key-value table for storing shop-specific configuration settings.
 *
 * Uses composite primary key (shopId, key) to ensure one unique setting per shop.
 * Supports different value types through valueType field for proper parsing.
 *
 * Example data:
 * - shopId=1, key="voucher_enabled", value="true", valueType="boolean"
 * - shopId=1, key="tse_mode", value="SIMULATION", valueType="string"
 * - shopId=1, key="max_discount", value="25.00", valueType="decimal"
 */
object ShopConfigurationTable : Table("shop_configuration") {
    /** Reference to the shop this configuration belongs to */
    val shopId = reference("shop_id", ShopInfoTable.id)

    /** Configuration key (unique per shop) */
    val key = varchar("key", 100)

    /** Configuration value stored as JSONB for efficient JSON operations */
    val value =
        jsonb<JsonElement>(
            name = "value",
            serialize = { Json.encodeToString(JsonElement.serializer(), it) },
            deserialize = { Json.parseToJsonElement(it) },
        )

    /** Optional description of the setting */
    val description = text("description").nullable()

    /** Timestamp of last update */
    val updatedAt = datetime("updated_at").nullable()

    /** Composite primary key: one unique key per shop */
    override val primaryKey = PrimaryKey(shopId, key)

    init {
        // Index for fast lookups
        index(true, shopId, key)
    }
}

/**
 * Enum class defining all available configuration keys with their defaults.
 *
 * Provides type-safe access to configuration keys and ensures consistency
 * across the application. Values are stored as JSONB, type is inferred from JsonElement.
 */
enum class ConfigKey(
    val key: String,
    val defaultValue: String,
    val description: String,
) {
    // Core Business Settings
    VOUCHER_CONFIG(
        "voucher_config",
        """{"enabled":false,"sources":[{"type":"INTERNAL","name":"Internal System Vouchers","enabled":true,"description":"Vouchers created and sold through the POS system"}]}""",
        "Voucher system configuration: Defines supported voucher sources (INTERNAL, LEGACY, EXTERNAL, PROMOTIONAL, GIFT_CARD)",
    ),

    // TSE and Compliance
    TSE_CONFIG(
        "tse_config",
        """{"mode":"DISABLED","timeAdminPin":"","keepAliveEnabled":true,"keepAliveIntervalSeconds":30,"failureBehavior":"QUEUE"}""",
        "TSE configuration: TimeAdmin PIN and hardware configuration. The Admin PIN, PUK and credential seed are deliberately absent — they are never stored. failureBehavior: QUEUE/FAIL",
    ),

    // Tax and Discounts
    VAT_MAPPING(
        "vat_mapping",
        """{"FOOD":{"default":"A","toGo":"B","reduced":"B"},"DRINKS":{"default":"A"},"RETAIL":{"default":"A"},"SERVICE":{"default":"A"},"VOUCHER":{"default":"C"},"OTHERS":{"default":"C"}}""",
        "VAT mapping configuration: Maps ProductType to VAT class (A=19%, B=7%, C=0%) based on conditions (default, toGo, reduced)",
    ),

    // Outgoing invoices
    AR_NUMBERING(
        "ar_numbering",
        """{"prefix":"RG","startValue":1,"padding":5}""",
        "Ausgangsrechnung numbering: prefix, first number and zero-padding width. The prefix must differ from the Kassenbeleg prefix (RE) — two ranges emitting one string is unrecoverable. startValue continues an existing sequence; it seeds a year's counter and never resets a running one.",
    ),

    // Currency
    DEFAULT_CURRENCY("default_currency", """{"currency": "EUR"}""", "Default currency code (ISO 4217)"),

    // Printing
    PRINT_SETTINGS("print_settings", """{"enabled":false}""", "Printer configuration enabled"),

    // Server Configuration
    SERVER_PORT(
        "server_port",
        """{"port":8080}""",
        "Server port configuration",
    ),

    // Backup and Restore Configuration
    BACKUP_CONFIG(
        "backup_config",
        """{"defaultBackupPath":null,"lastBackupAt":null}""",
        "Database backup configuration: Default backup path and last backup timestamp",
    ),

    // PDF Storage Configuration
    DOCUMENT_STORAGE_CONFIG(
        "pdf_storage_config",
        """{"path":"","maxUploadSizeMb":20,"additionalDocumentTypes":[]}""",
        "PDF storage configuration: storage path, upload limits, and additional document types",
    ),

    // DATEV XML Schnittstelle Configuration
    DATEV_XML_CONFIG(
        "datev_xml_config",
        """{"enabled":false,"kassenName":"Kasse","exportPath":"","consultantNumber":"","clientNumber":"","datevExportCadence":"MONTHLY"}""",
        "DATEV XML Schnittstelle configuration: cash account, name, export storage path, consultant/client numbers, export cadence",
    ),

    // DATEV account mapping (Kassenbuch line kinds -> DATEV accounts)
    DATEV_ACCOUNT_MAPPING(
        "datev_account_mapping",
        "",
        "DATEV account mapping: per line-kind/VAT-rate Gegenkonten, Kasse-Konto and per-payment-type clearing accounts for Buchungsstapel/XML exports. Empty = unconfigured (legacy defaults).",
    ),

    // DATEV Ausgangsrechnungen (AR) Sammeldebitor
    DATEV_AR_SAMMELDEBITOR(
        "datev_ar_sammeldebitor",
        "",
        "DATEV Debitoren-Sammelkonto (Personenkonto) for the Ausgangsrechnungen/AR export. " +
            "Empty = unconfigured, no AR export until set.",
    ),

    CASH_BOOK_TRANSFER_TARGET(
        "cash_book_transfer_target",
        "",
        "Cash book id that receives the daily takings when a Z-Bon is created (Geldtransit). " +
            "Empty = no automatic transfer, which is the default and today's behaviour.",
    ),

    /**
     * How many days back a new Kassenbuch entry's Beleg (receipt) date may lie. The Beleg date no
     * longer decides the period — the booking time does — so this is the only backdating bound.
     * See `KassenbuchBelegDateRule` in `shared`.
     */
    KASSENBUCH_MAX_BELEG_AGE_DAYS(
        "kassenbuch_max_beleg_age_days",
        "31",
        "Maximum age in days of a Kassenbuch entry's Beleg date (1..366). Default 31 = one month.",
    ),

    /**
     * The date open-item tracking starts from, as `yyyy-MM-dd`.
     *
     * **The declared default is empty on purpose.** [defaultValue] is a compile-time constant, and a
     * date written here would be stale before the release ships. Blank means "not yet chosen";
     * `BookkeepingStartService` writes the first day of the current month on first read.
     */
    BOOKKEEPING_START(
        "bookkeeping_start",
        "",
        "Buchungsbeginn: invoices and bank transactions dated before this are visible and " +
            "exportable but never counted as open items or assignment-queue work.",
    ),

    /**
     * Whether the operator has been shown, and dismissed, the Buchungsbeginn the system chose.
     *
     * Durable on purpose. The banner used to be driven by a one-shot `wasDefaulted` flag on the
     * response that happened to create the setting — an affordance tied to a single non-idempotent
     * response is lost forever to a reload at the wrong moment, and now also to whichever list
     * request happens to run first, since every list read creates the default too.
     */
    BOOKKEEPING_START_ACKNOWLEDGED(
        "bookkeeping_start_acknowledged",
        "false",
        "Buchungsbeginn acknowledgement: true once a manager has seen the self-dated default and " +
            "either dismissed the banner or chosen a date of their own.",
    ),

    /**
     * Restored filesystem paths awaiting an administrator's review, as `{"<RestoredPathSetting>":
     * "<restored path>"}`. Written by the restore itself, inside the restore transaction, so a
     * backup's own copy of this row never survives; `{}` (or no row) means nothing is pending. While
     * an entry is present every file write to that path is paused — see `RestoredPathSetting`.
     */
    RESTORED_PATHS_REVIEW(
        "restored_paths_review",
        "{}",
        "Restored folder paths that point outside this installation and await review. File writes " +
            "to a listed path are paused until an administrator keeps it or switches to the default.",
    ),

    // Order Rules Configuration
    ORDER_RULES(
        "order_rules",
        """{"employeeSelectionOnFreeTable":false,"roundRobinAssignment":false}""",
        "Order processing rules: employee selection requirement; round-robin assignment when opening free tables",
    ),

    ORDER_ROTATION_CURSOR(
        "order_rotation_cursor",
        """{"lastAssignedEmployeeId":null}""",
        "Round-robin rotation cursor: id of the last employee assigned via the round-robin order rule",
    ),

    // Billing Rules Configuration
    BILLING_RULES(
        "billing_rules",
        """{"allowCrossEmployeeBilling":false}""",
        "Billing rules: allow any cashier to bill any table session",
    ),

    // Browser stations: terminal roles that may run in a browser tab instead of a native client
    BROWSER_STATIONS(
        "browser_stations",
        """{"roles":[]}""",
        "Terminal roles that may run as browser stations",
    ),

    // Web access: whether /swagger, /openapi and /openapi-generated.json are served (server-wide, first shop)
    API_DOCS_ENABLED(
        "api_docs_enabled",
        "false",
        "Serve the API documentation at /swagger and /openapi",
    ),

    // Web access: where / forwards a browser that has not chosen its own default (none | admin | pos)
    START_PAGE_DEFAULT(
        "start_page_default",
        "none",
        "Server-wide default page for /",
    ),

    // Break Rules Configuration
    BREAK_RULES_CONFIG(
        "break_rules_config",
        """{"rules":[{"thresholdMinutes":180,"breakMinutes":15}]}""",
        "Break rules configuration: threshold-based mandatory break durations per ArbZG §4",
    ),

    // ZVT Card Terminal Configuration
    ZVT_CONFIG(
        "zvt_config",
        """{"enabled":false}""",
        "ZVT card terminal configuration: Enable/disable card terminal integration features",
    ),

    // Kassenmeldung (ELSTER § 146a Abs. 4 AO)
    KASSEN_MELDUNG_CONFIG(
        "kassen_meldung_config",
        """{"kasseSeriennr":"","kasseBrand":"","kasseModell":"Desktop/Server POS","kasseSwBrand":"","kasseSwVersion":"","artDesAufzeichnungssystems":"Computergestützte/PC-Kassensysteme","inbetriebnahme":""}""",
        "Kassenmeldung configuration: serial number, brand, model, software info for ELSTER/DSFinV-K compliance",
    ),

    // Authentication Session Policy
    TOKEN_SESSION_POLICY(
        "token_session_policy",
        """{"dailyResetEnabled":true,"dailyResetTime":"04:00","manualCutoffAt":null}""",
        "Session policy: daily forced re-login time (HH:mm) and manual global session cutoff (epoch seconds)",
    ),
    ;

    companion object {
        /**
         * Find configuration key by key string.
         */
        fun fromKey(key: String): ConfigKey? = entries.find { it.key == key }

        /**
         * Get all keys as a map of key -> ConfigKey.
         */
        fun asMap(): Map<String, ConfigKey> = entries.associateBy { it.key }
    }
}
