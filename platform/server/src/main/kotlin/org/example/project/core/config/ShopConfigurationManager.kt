package org.example.project.core.config

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.domain.enums.websocket.DataChangeOperation
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.websocket.NotificationService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.di.AppJson
import org.example.project.utils.OsUtils
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.getTimeZone
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime as KxLocalDateTime

/**
 * Thread-safe singleton manager for shop configuration settings.
 *
 * Provides cached access to shop configuration with automatic refresh
 * and fallback to sensible defaults. Optimized for high-frequency access
 * patterns like TSE operations.
 */
object ShopConfigurationManager : IShopConfigurationManager, KoinComponent {
    private val logger = LoggerFactory.getLogger(ShopConfigurationManager::class.java)

    /** JSON serializer from DI with encodeDefaults = true */
    private val json: Json by inject(AppJson)

    /** Mutex for thread-safe configuration updates */
    private val configMutex = Mutex()

    /** Cached configuration data */
    private val cachedConfig = AtomicReference<CachedConfiguration?>(null)

    /** Cache validity duration */
    private val cacheValidityDuration = 30.minutes

    /** Configuration repository for database operations */
    private val configRepository: ConfigurationRepository by inject()

    /**
     * Data class for cached configuration with metadata.
     */
    private data class CachedConfiguration(
        val shopId: Long,
        val settings: Map<String, JsonElement>,
        val loadedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    ) {
        /** Checks if the cached configuration is still valid */
        fun isValid(): Boolean {
            val now = LocalDateTime.now()
            return now.isBefore(expiresAt)
        }

        /** Checks if configuration is expired and needs refresh */
        fun isExpired(): Boolean = !isValid()

        /** Gets a setting value as JsonElement or returns default */
        fun getSetting(key: ConfigKey): JsonElement = settings[key.key] ?: JsonPrimitive(key.defaultValue)

        /** Gets a JSON object setting, returns null if not an object */
        fun getJsonObject(key: ConfigKey): JsonObject? = settings[key.key] as? JsonObject

        /** Gets a string setting */
        fun getString(key: ConfigKey): String =
            settings[key.key]?.let { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull ?: element.toString()
                    is JsonObject, is JsonArray -> element.toString()
                }
            } ?: key.defaultValue
    }

    /**
     * Initializes the configuration manager by loading configuration from database.
     *
     * Should be called during server startup. Safe to call multiple times.
     * If no configuration exists, creates default configuration.
     *
     * @return true if initialization was successful, false otherwise
     */
    override suspend fun initialize(): Boolean =
        try {
            configMutex.withLock {
                logger.info("Initializing ShopConfigurationManager...")

                val config = loadConfigurationFromDatabase()
                if (config != null) {
                    cachedConfig.set(config)
                    logger.info("Configuration loaded successfully for shop ID: ${config.shopId}")
                    true
                } else {
                    logger.warn("No shop configuration found, will use defaults")
                    false
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize ShopConfigurationManager", e)
            false
        }

    /**
     * Gets the current TSE mode from TSE configuration.
     *
     * @return Current TSE mode from TseConfig, or DISABLED as fallback
     */
    override suspend fun getTseMode(): TseMode = getTseConfig().mode

    /**
     * Gets the default currency code.
     *
     * @return Default currency code (ISO 4217)
     */
    override suspend fun getDefaultCurrency(): String {
        return getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DEFAULT_CURRENCY)
            ?.get("currency") // Access the "currency" field
            ?.let { (it as? JsonPrimitive)?.content } // Extract string content
            // A blank stored value must fall back too, not just a missing one. A shop whose config
            // holds {"currency": ""} otherwise pushes "" into every money response, and the clients
            // render unlabelled amounts ("9,70" instead of "9,70 €") because symbolFor("") is null.
            ?.takeIf { it.isNotBlank() }
            ?: "EUR" // Default fallback
    }

    /**
     * Gets the voucher system configuration as JSON string.
     *
     * Returns the complete voucher configuration including supported sources,
     * validation rules, and global settings.
     *
     * @return Voucher configuration JSON string
     */

    /**
     * Gets a cached setting string synchronously (non-suspend).
     * Returns null if config is not yet loaded.
     */
    override fun getCachedSettingString(key: ConfigKey): String? = cachedConfig.get()?.getString(key)

    override suspend fun getVoucherConfig(): JsonElement = getOrRefreshConfiguration()?.getSetting(ConfigKey.VOUCHER_CONFIG) ?: JsonNull

    /**
     * Gets the VAT mapping configuration as JSON string.
     *
     * The JSON structure maps ProductType to VAT classes with conditions:
     * ```
     * {
     *   "FOOD": {
     *     "default": "A",
     *     "toGo": "B",
     *     "reduced": "B"
     *   },
     *   "DRINKS": {
     *     "default": "A"
     *   }
     * }
     * ```
     *
     * @return VAT mapping configuration JSON string
     */
    override suspend fun getVatMapping(): JsonElement = getOrRefreshConfiguration()?.getSetting(ConfigKey.VAT_MAPPING) ?: JsonNull

    override suspend fun <T> getDecodedSetting(
        key: ConfigKey,
        serializer: KSerializer<T>,
    ): T? {
        val obj = getOrRefreshConfiguration()?.getJsonObject(key) ?: return null
        return json.decodeFromJsonElement(serializer, obj)
    }

    /**
     * Gets the cash book that receives the daily takings when a Z-Bon is created, or null when
     * the automatic Geldtransit is switched off (the default / today's behaviour).
     *
     * `toLongOrNull()` rather than `toLong()`: a malformed key must disable the transfer, not
     * crash every Z-Bon — this getter sits on the fiscal closing path.
     */
    override suspend fun getCashBookTransferTargetId(): Long? =
        getOrRefreshConfiguration()
            ?.getString(ConfigKey.CASH_BOOK_TRANSFER_TARGET)
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()

    /**
     * The shop's Beleg-age limit for new Kassenbuch entries. A malformed or out-of-range value
     * falls back to the default rather than failing every cash-book entry.
     */
    override suspend fun getKassenbuchMaxBelegAgeDays(): Int =
        KassenbuchBelegDateRule.effectiveMaxAgeDays(
            getOrRefreshConfiguration()
                ?.getString(ConfigKey.KASSENBUCH_MAX_BELEG_AGE_DAYS)
                ?.trim()
                ?.toIntOrNull(),
        )

    /**
     * Gets the printer settings enabled/disabled state.
     *
     * Returns whether the printer configuration is currently enabled for the shop.
     *
     * @return true if printer settings are enabled, false otherwise
     */
    override suspend fun isPrinterSettingsEnabled(): Boolean {
        return getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.PRINT_SETTINGS)
            ?.get("enabled")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false // Default to disabled
    }

    /**
     * Gets whether ZVT card terminal integration is enabled.
     *
     * @return true if ZVT card terminal integration is enabled, false otherwise
     */
    override suspend fun isZvtEnabled(): Boolean =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.ZVT_CONFIG)
            ?.get("enabled")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false

    // ============================================
    // Billing Rules Configuration Methods
    // ============================================

    /**
     * Gets whether any cashier can create bills for any table session.
     *
     * @return true if cross-employee billing is allowed, false otherwise
     */
    override suspend fun isAllowCrossEmployeeBilling(): Boolean =
        try {
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.BILLING_RULES)
                ?.get("allowCrossEmployeeBilling")
                ?.let { (it as? JsonPrimitive)?.booleanOrNull }
                ?: false
        } catch (e: Exception) {
            logger.error("Failed to read billing rules config", e)
            false
        }

    override suspend fun isRoundRobinAssignment(): Boolean =
        try {
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.ORDER_RULES)
                ?.get("roundRobinAssignment")
                ?.let { (it as? JsonPrimitive)?.booleanOrNull }
                ?: false
        } catch (e: Exception) {
            logger.error("Failed to read order rules config", e)
            false
        }

    /**
     * Fails closed. A shop with no `browser_stations` row makes the settings map answer with the
     * default as a *string*, so it is read as an object and anything that is not one is empty.
     * Only CASHIER and ORDER_STATION can ever be enabled; any other stored value is dropped.
     */
    override suspend fun getBrowserStationRoles(): Set<TerminalRole> =
        try {
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.BROWSER_STATIONS)
                ?.get("roles")
                ?.jsonArray
                ?.mapNotNull { TerminalRole.parseOrNull(it.jsonPrimitive.content) }
                ?.filter { it == TerminalRole.CASHIER || it == TerminalRole.ORDER_STATION }
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            logger.error("Failed to read browser stations config; treating as disabled", e)
            emptySet()
        }

    override suspend fun isApiDocsSettingEnabled(): Boolean =
        try {
            getOrRefreshConfiguration()
                ?.let { it.getSetting(ConfigKey.API_DOCS_ENABLED) as? JsonPrimitive }
                ?.content
                ?.trim()
                ?.equals("true", ignoreCase = true)
                ?: false
        } catch (e: Exception) {
            logger.error("Failed to read api_docs_enabled; treating as off", e)
            false
        }

    override suspend fun getStartPageDefault(): StartPageTarget =
        try {
            getOrRefreshConfiguration()
                ?.let { it.getSetting(ConfigKey.START_PAGE_DEFAULT) as? JsonPrimitive }
                ?.content
                .let(StartPageTarget::fromWire)
                ?: StartPageTarget.NONE
        } catch (e: Exception) {
            logger.error("Failed to read start_page_default; treating as none", e)
            StartPageTarget.NONE
        }

    override suspend fun getServerPort(): Int =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.SERVER_PORT)
            ?.get("port")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 8080

    // ============================================
    // PDF Storage Configuration Methods
    // ============================================

    /**
     * Gets the configured document storage path.
     *
     * Returns the path from configuration, or falls back to
     * [OsUtils.getDefaultStorageDirectory] if the configured path is empty.
     *
     * @return Effective document storage directory path
     */
    override suspend fun getDocumentStoragePath(): String {
        val config = getOrRefreshConfiguration()
        // A restored document path that belongs to another installation is not used until an
        // admin has reviewed it: documents are read from and written to this installation's
        // default meanwhile — which is also where the restore put the archive's files. Unlike the
        // DATEV/backup paths this one cannot simply refuse, because Z-Bon and invoice PDFs are
        // written on the fiscal closing path.
        if (RestoredPathSetting.DOCUMENT_STORAGE_PATH in pendingReview(config)) {
            logger.debug("getDocumentStoragePath: restored path awaits review, using the local default")
            return OsUtils.getDefaultStorageDirectory()
        }
        val configuredPath =
            config
                ?.getJsonObject(ConfigKey.DOCUMENT_STORAGE_CONFIG)
                ?.get("path")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: ""

        return configuredPath.ifBlank {
            OsUtils.getDefaultStorageDirectory()
        }
    }

    override suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> = pendingReview(getOrRefreshConfiguration())

    private fun pendingReview(config: CachedConfiguration?): Map<RestoredPathSetting, String> =
        RestoredPathPolicy.decodeReview(config?.settings?.get(ConfigKey.RESTORED_PATHS_REVIEW.key))

    /**
     * Gets the list of additional custom document types from PDF storage config.
     *
     * These are user-defined document type folders beyond the default [DocumentType] enum entries.
     *
     * @return List of additional document type names (folder names)
     */
    override suspend fun getAdditionalDocumentTypes(): List<String> =
        try {
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.DOCUMENT_STORAGE_CONFIG)
                ?.get("additionalDocumentTypes")
                ?.let { element ->
                    if (element is JsonArray) {
                        element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    } else {
                        emptyList()
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to parse additional document types, returning empty list", e)
            emptyList()
        }

    // ============================================
    // Backup Configuration Methods
    // ============================================

    /**
     * Gets the default backup path from backup configuration.
     *
     * @return Configured default backup path, or null if not set
     */
    override suspend fun getDefaultBackupPath(): String? =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("defaultBackupPath")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it != "null" }

    /**
     * Gets whether automatic backups are enabled.
     *
     * @return true if auto-backup is enabled, false otherwise (default false)
     */
    override suspend fun getBackupAutoEnabled(): Boolean =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("autoEnabled")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false

    /**
     * Gets the cadence at which automatic backups run.
     *
     * @return BackupCadence, defaults to DAILY
     */
    override suspend fun getBackupCadence(): BackupRestoreDto.BackupCadence {
        val raw =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
                ?.get("cadence")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (raw != null) {
            try {
                BackupRestoreDto.BackupCadence.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                BackupRestoreDto.BackupCadence.DAILY
            }
        } else {
            BackupRestoreDto.BackupCadence.DAILY
        }
    }

    /**
     * Gets the number of backups to retain before pruning.
     *
     * @return retention count, defaults to 7
     */
    override suspend fun getBackupRetentionCount(): Int =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("retentionCount")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 7

    /**
     * Gets the hour of day (0..23) at which automatic backups run.
     *
     * @return run hour, defaults to 3
     */
    override suspend fun getBackupRunHour(): Int =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("runHour")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 3

    /**
     * Gets the timestamp of the last successful backup.
     *
     * @return ISO-8601 timestamp string, or null if never backed up
     */
    override suspend fun getLastBackupAt(): String? =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("lastBackupAt")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it != "null" }

    override suspend fun getBackupCopyTarget(): BackupRestoreDto.BackupCopyTargetDto? =
        BackupConfigJson.copyTarget(getOrRefreshConfiguration()?.getJsonObject(ConfigKey.BACKUP_CONFIG))

    override suspend fun getLastBackupCopyAt(): String? = backupConfigText("lastCopyAt")

    override suspend fun getLastBackupCopyStatus(): BackupCopyStatus? =
        BackupConfigJson.copyStatus(getOrRefreshConfiguration()?.getJsonObject(ConfigKey.BACKUP_CONFIG))

    override suspend fun getLastBackupCopyError(): String? = backupConfigText("lastCopyError")

    private suspend fun backupConfigText(key: String): String? =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get(key)
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it != "null" }

    override suspend fun getBackupCopyRetentionCount(): Int =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
            ?.get("copyRetentionCount")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 7

    override suspend fun getBackupMaxUploadBytes(): Long {
        val configured =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.BACKUP_CONFIG)
                ?.get("maxUploadBytes")
                ?.let { (it as? JsonPrimitive)?.longOrNull }
        return configured ?: DEFAULT_MAX_BACKUP_UPLOAD_BYTES
    }

    // ============================================
    // DATEV XML Schnittstelle Configuration Methods
    // ============================================

    /**
     * Gets the DATEV XML Kassen-Name (cash register name).
     *
     * @return Cash register name (default "Kasse")
     */
    override suspend fun getDatevXmlKassenName(): String =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("kassenName")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: "Kasse"

    /**
     * Gets the DATEV XML export storage path on the server.
     *
     * Returns empty string if not configured, meaning no server-side copy is saved.
     *
     * @return Server-side export path or empty string
     */
    override suspend fun getDatevXmlExportPath(): String =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("exportPath")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: ""

    /**
     * Gets the DATEV XML Beraternummer (consultant number).
     *
     * @return Consultant number or null if not configured
     */
    override suspend fun getDatevXmlConsultantNumber(): String? =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("consultantNumber")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

    /**
     * Gets the DATEV XML Mandantennummer (client number).
     *
     * @return Client number or null if not configured
     */
    override suspend fun getDatevXmlClientNumber(): String? =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("clientNumber")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }

    /**
     * Gets the DATEV Debitoren-Sammelkonto (Sammeldebitor) Personenkonto for the AR export
     * (slice 4d).
     *
     * @return Sammeldebitor Personenkonto or null if not configured
     */
    override suspend fun getDatevArSammeldebitor(): String? =
        getOrRefreshConfiguration()
            ?.getString(ConfigKey.DATEV_AR_SAMMELDEBITOR)
            ?.takeIf { it.isNotBlank() }

    /**
     * Gets the DATEV AP export mode (MINIMAL or BEST_EFFORT).
     *
     * @return Export mode string, defaults to "BEST_EFFORT"
     */
    override suspend fun getDatevApExportMode(): String =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("apExportMode")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it in listOf("MINIMAL", "BEST_EFFORT") }
            ?: "BEST_EFFORT"

    /**
     * Gets the DATEV export cadence (WEEKLY, MONTHLY, or MANUAL).
     *
     * @return DatevExportCadence, defaults to MONTHLY
     */
    override suspend fun getDatevExportCadence(): DatevExportCadence {
        val raw =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
                ?.get("datevExportCadence")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (raw != null) {
            try {
                DatevExportCadence.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                DatevExportCadence.MONTHLY
            }
        } else {
            DatevExportCadence.MONTHLY
        }
    }

    /**
     * Gets whether automatic DATEV exports are enabled.
     *
     * @return true if auto-export enabled, defaults to false
     */
    override suspend fun getDatevAutoEnabled(): Boolean =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("enabled")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false

    /**
     * Gets the per-type cadence for DATEV Kassenbuch exports.
     *
     * @return DatevAutoCadence, defaults to OFF
     */
    override suspend fun getDatevKassenbuchCadence(): DatevAutoCadence = readDatevAutoCadence("kassenbuchCadence")

    /**
     * Gets the per-type cadence for DATEV AP-Ledger exports.
     *
     * @return DatevAutoCadence, defaults to OFF
     */
    override suspend fun getDatevApLedgerCadence(): DatevAutoCadence = readDatevAutoCadence("apLedgerCadence")

    /**
     * Gets the per-type cadence for DATEV Buchungsstapel exports.
     *
     * @return DatevAutoCadence, defaults to OFF
     */
    override suspend fun getDatevBuchungsstapelCadence(): DatevAutoCadence = readDatevAutoCadence("buchungsstapelCadence")

    /**
     * Gets the hour of day (0..23) at which automatic DATEV exports run.
     *
     * @return run hour, defaults to 4
     */
    override suspend fun getDatevRunHour(): Int =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("runHour")
            ?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: 4

    private suspend fun readDatevAutoCadence(field: String): DatevAutoCadence {
        val raw =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
                ?.get(field)
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (raw != null) {
            try {
                DatevAutoCadence.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                DatevAutoCadence.OFF
            }
        } else {
            DatevAutoCadence.OFF
        }
    }

    /**
     * Gets the Wirtschaftsjahr-Beginn for DATEV Buchungsstapel exports.
     *
     * Returns 1 January of the current year if not configured.
     *
     * @return ISO date string "YYYY-MM-DD"
     */
    suspend fun getDatevWirtschaftsjahrBeginn(): java.time.LocalDate {
        val raw =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
                ?.get("wirtschaftsjahrBeginn")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return if (raw.isNullOrBlank()) {
            java.time.LocalDate.of(
                java.time.LocalDate
                    .now()
                    .year,
                1,
                1,
            )
        } else {
            try {
                java.time.LocalDate.parse(raw)
            } catch (_: Exception) {
                java.time.LocalDate.of(
                    java.time.LocalDate
                        .now()
                        .year,
                    1,
                    1,
                )
            }
        }
    }

    /**
     * Gets the Sachkontenlänge (chart-of-accounts account-number length) for
     * DATEV Buchungsstapel exports. Must be 4..8.
     *
     * @return Integer, default 4
     */
    suspend fun getDatevSachkontenlaenge(): Int {
        val raw =
            getOrRefreshConfiguration()
                ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
                ?.get("sachkontenlaenge")
                ?.let { (it as? JsonPrimitive)?.intOrNull }
        return (raw ?: 4).coerceIn(4, 8)
    }

    /**
     * Gets the Festschreibung flag for DATEV Buchungsstapel exports.
     *
     * When true, bookings are imported as locked in DATEV (cannot be edited
     * further). Default false lets the advisor still adjust entries in DATEV.
     *
     * @return Boolean, default false
     */
    suspend fun getDatevFestschreibung(): Boolean =
        getOrRefreshConfiguration()
            ?.getJsonObject(ConfigKey.DATEV_XML_CONFIG)
            ?.get("festschreibung")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false

    // ============================================
    // TSE Configuration Methods
    // ============================================

    /**
     * Gets the TSE configuration settings.
     *
     * @return TseConfigDto.TseConfig or default if not found
     */

    override suspend fun getTseConfig(): TseConfigDto.TseConfig =
        try {
            val config = getOrRefreshConfiguration()
            val tseJson = config?.getString(ConfigKey.TSE_CONFIG)

            if (tseJson != null) {
                json.decodeFromString<TseConfigDto.TseConfig>(tseJson)
            } else {
                logger.debug("No TSE config found, creating default configuration")
                TseConfigDto.TseConfig(timeAdminPin = "12345")
            }
        } catch (e: Exception) {
            logger.error("Failed to parse TSE config, returning defaults", e)
            TseConfigDto.TseConfig(timeAdminPin = "12345")
        }

    /**
     * Sets the TSE configuration settings.
     *
     * @param config TSE configuration to save
     */
    override suspend fun setTseConfig(config: TseConfigDto.TseConfig) {
        try {
            dbQuery {
                val shop =
                    ShopInfo.all().limit(1).firstOrNull()
                        ?: throw IllegalStateException("No shop found in database")

                val jsonString = json.encodeToString(config)
                configRepository.setSetting(shop.id.value, ConfigKey.TSE_CONFIG, jsonString)
                logger.info("TSE configuration saved successfully")
                refreshConfiguration()
            }
            // Invalidate cache
            cachedConfig.set(null)
        } catch (e: Exception) {
            logger.error("Failed to save TSE config", e)
            throw e
        }
    }

    /**
     * Forces a refresh of the cached configuration from the database.
     *
     * Useful when configuration has been updated and immediate refresh is needed.
     *
     * @return true if refresh was successful, false otherwise
     */
    override suspend fun refreshConfiguration(): Boolean =
        try {
            configMutex.withLock {
                logger.debug("Forcing configuration refresh...")
                val config = loadConfigurationFromDatabase()
                if (config != null) {
                    cachedConfig.set(config)

                    NotificationService.notifyDataChange(
                        ChangeEntityType.SHOP_CONFIGURATION,
                        DataChangeOperation.UPDATED,
                    )

                    logger.debug("Configuration refreshed successfully")
                    true
                } else {
                    logger.warn("No configuration found during refresh")
                    false
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to refresh configuration", e)
            false
        }

    // ============================================
    // Rotation Cursor Methods
    // ============================================

    /**
     * Gets the last-assigned employee id for the round-robin rotation cursor, read from the DATABASE
     * row under `FOR UPDATE` in the caller's transaction — never from the configuration cache.
     *
     * emu20 NOTE-r: two station opens a second apart both went to the same waiter. The cursor used to
     * live in the shared cache, which [setOrderRotationCursor] filled with a pick that was still
     * uncommitted, and which ANY request's transaction may reload on expiry or after the
     * `cachedConfig.set(null)` that preceded that refresh — a concurrent takeaway in the QA burst. A
     * reload in a transaction that cannot see the uncommitted pick put the old cursor back, and the
     * next pick handed out the same employee. Read from the row, the pick sees exactly what the
     * database has: a concurrent pick's uncommitted write blocks on the row lock, and once it
     * commits the waiter either reads it (READ COMMITTED) or fails with a serialization error that
     * the order path's retry repeats with a fresh snapshot (REPEATABLE READ). A rolled-back pick
     * rolls the cursor back with it, so no cache needs invalidating (T9-18).
     *
     * @return UUID of the last assigned employee, or null if the cursor has never been set
     */
    override suspend fun getOrderRotationCursor(): UUID? =
        dbQuery {
            val shopId =
                ShopInfo
                    .all()
                    .limit(1)
                    .firstOrNull()
                    ?.id
                    ?.value ?: return@dbQuery null
            val key = ConfigKey.ORDER_ROTATION_CURSOR.key
            // Create the row first if it is missing, so FOR UPDATE has something to lock: two first-ever
            // picks would otherwise both read "no cursor" and both hand out the pool's first employee.
            val value =
                configRepository.getSettingForUpdate(shopId, key)
                    ?: run {
                        configRepository.setSetting(shopId, ConfigKey.ORDER_ROTATION_CURSOR, ConfigKey.ORDER_ROTATION_CURSOR.defaultValue)
                        configRepository.getSettingForUpdate(shopId, key)
                    }
            val raw = (value as? JsonObject)?.get("lastAssignedEmployeeId")?.jsonPrimitive?.contentOrNull
            raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }

    /**
     * Plain read of the rotation cursor row: no `FOR UPDATE`, no row creation, no write. Null when the
     * shop, the row or the value is missing. Display use only — a pick reads via [getOrderRotationCursor].
     */
    override suspend fun peekOrderRotationCursor(): UUID? =
        dbQuery {
            val shopId =
                ShopInfo
                    .all()
                    .limit(1)
                    .firstOrNull()
                    ?.id
                    ?.value ?: return@dbQuery null
            val value = configRepository.getSetting(shopId, ConfigKey.ORDER_ROTATION_CURSOR.key)
            val raw = (value as? JsonObject)?.get("lastAssignedEmployeeId")?.jsonPrimitive?.contentOrNull
            raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }

    /**
     * Persists the round-robin rotation cursor in the caller's transaction. Deliberately leaves the
     * configuration cache alone: the cursor is read only from the row ([getOrderRotationCursor]), and
     * putting an uncommitted pick into a process-wide cache is what let other requests see — and then
     * overwrite — a value the database did not yet hold (emu20 NOTE-r).
     *
     * @param employeeId UUID of the employee that was just assigned, or null to reset the cursor
     */
    override suspend fun setOrderRotationCursor(employeeId: UUID?) {
        try {
            dbQuery {
                val shop =
                    ShopInfo.all().limit(1).firstOrNull()
                        ?: throw IllegalStateException("No shop found in database")
                val cursorJson = """{"lastAssignedEmployeeId":${if (employeeId == null) "null" else "\"$employeeId\""}}"""
                configRepository.setSetting(shop.id.value, ConfigKey.ORDER_ROTATION_CURSOR, cursorJson)
                logger.debug("setOrderRotationCursor: cursor={}", employeeId)
            }
        } catch (e: Exception) {
            logger.error("setOrderRotationCursor: failed to save cursor", e)
            throw e
        }
    }

    // ============================================
    // Token Session Policy Methods
    // ============================================

    override suspend fun getTokenSessionPolicy(): IShopConfigurationManager.TokenSessionPolicy {
        val raw =
            getOrRefreshConfiguration()?.getJsonObject(ConfigKey.TOKEN_SESSION_POLICY)
                ?: json.parseToJsonElement(ConfigKey.TOKEN_SESSION_POLICY.defaultValue).jsonObject
        return parsePolicy(raw)
    }

    override suspend fun setTokenDailyReset(
        enabled: Boolean,
        resetTime: String,
    ) {
        val current = getTokenSessionPolicy()
        writePolicy(current.copy(dailyResetEnabled = enabled, dailyResetTime = resetTime))
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun triggerManualSessionCutoff() {
        val current = getTokenSessionPolicy()
        writePolicy(current.copy(manualCutoffAt = Clock.System.now().epochSeconds))
    }

    @OptIn(ExperimentalTime::class)
    override fun effectiveSessionCutoffEpochSeconds(): Long {
        val raw =
            getCachedSettingString(ConfigKey.TOKEN_SESSION_POLICY)
                ?: ConfigKey.TOKEN_SESSION_POLICY.defaultValue
        val policy = parsePolicy(json.parseToJsonElement(raw).jsonObject)
        val manual = policy.manualCutoffAt ?: 0L
        val daily = if (policy.dailyResetEnabled) mostRecentResetEpoch(policy.dailyResetTime) else 0L
        return max(manual, daily)
    }

    private fun parsePolicy(obj: JsonObject): IShopConfigurationManager.TokenSessionPolicy =
        IShopConfigurationManager.TokenSessionPolicy(
            dailyResetEnabled = (obj["dailyResetEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            dailyResetTime = (obj["dailyResetTime"] as? JsonPrimitive)?.contentOrNull ?: "04:00",
            manualCutoffAt = (obj["manualCutoffAt"] as? JsonPrimitive)?.longOrNull,
        )

    private suspend fun writePolicy(policy: IShopConfigurationManager.TokenSessionPolicy) {
        val shopId = getOrRefreshConfiguration()?.shopId ?: return

        @Suppress("ktlint:standard:max-line-length") // single-line config JSON
        val jsonStr = """{"dailyResetEnabled":${policy.dailyResetEnabled},"dailyResetTime":"${policy.dailyResetTime}","manualCutoffAt":${policy.manualCutoffAt ?: "null"}}"""
        // setSetting is an Exposed repository call and needs an ambient transaction, exactly like the
        // other writers in this class (setTseConfig, setOrderRotationCursor). Without it every policy
        // save and every "log everyone out" answered 500 "No transaction in context" (T9-2).
        dbQuery { configRepository.setSetting(shopId, ConfigKey.TOKEN_SESSION_POLICY, jsonStr) }
        refreshConfiguration()
    }

    @OptIn(ExperimentalTime::class)
    private fun mostRecentResetEpoch(resetTime: String): Long {
        val tz = getTimeZone()
        val now = Clock.System.now()
        val parts = resetTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 4
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val today = now.toLocalDateTime(tz).date
        var boundary = KxLocalDateTime(today.year, today.monthNumber, today.dayOfMonth, h, m).toInstant(tz)
        if (boundary > now) boundary = boundary.minus(DateTimePeriod(days = 1), tz)
        return boundary.epochSeconds
    }

    /**
     * Gets or refreshes configuration if cache is expired.
     *
     * @return Cached configuration or null if unavailable
     */
    private suspend fun getOrRefreshConfiguration(): CachedConfiguration? {
        val current = cachedConfig.get()

        // Return valid cached configuration
        if (current != null && current.isValid()) {
            return current
        }

        // Refresh if expired or not loaded
        return if (current == null || current.isExpired()) {
            if (refreshConfiguration()) {
                cachedConfig.get()
            } else {
                current // Return expired config as fallback
            }
        } else {
            current
        }
    }

    /**
     * Loads configuration from database.
     *
     * @return Loaded configuration or null if not found
     */
    private suspend fun loadConfigurationFromDatabase(): CachedConfiguration? {
        return try {
            dbQuery {
                // Get the first shop
                val shop = ShopInfo.all().limit(1).firstOrNull()

                if (shop != null) {
                    val shopId = shop.id.value

                    // Get all settings for this shop
                    val set = configRepository.getAllSettings(shopId)

                    // Initialize defaults if empty
                    if (set.isEmpty()) {
                        logger.info("No settings found for shopId=$shopId, initializing defaults")
                        configRepository.initializeDefaults(shopId)
                        // Reload after initialization
                        return@dbQuery loadConfigurationFromDatabase()
                    }

                    val settings = configRepository.getAllSettings(shopId)

                    val loadedAt = LocalDateTime.now()
                    CachedConfiguration(
                        shopId = shopId,
                        settings = settings,
                        loadedAt = loadedAt,
                        expiresAt = loadedAt.plusMinutes(cacheValidityDuration.inWholeMinutes),
                    )
                } else {
                    logger.warn("No shop found in database")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load configuration from database", e)
            null
        }
    }

    // Default: 2 GB — suitable for most deployments
    // Ops can raise via BACKUP_CONFIG.maxUploadBytes config setting
    private const val DEFAULT_MAX_BACKUP_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
}
