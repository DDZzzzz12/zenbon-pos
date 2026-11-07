/**
 * Interface for accessing shop configuration settings.
 *
 * Provides cached access to shop configuration with automatic refresh
 * and fallback to sensible defaults. Feature modules depend on this
 * interface; the concrete implementation lives in pos-server.
 *
 * @since 1.0.0
 */
package org.example.project.core.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.setup.database.tables.ConfigKey
import java.util.UUID

/**
 * Contract for shop configuration access.
 *
 * Implementations provide cached, thread-safe access to shop configuration
 * settings stored in the database.
 */
interface IShopConfigurationManager {
    /** Initializes the configuration manager by loading configuration from database. */
    suspend fun initialize(): Boolean

    /** Gets the current TSE mode from TSE configuration. */
    suspend fun getTseMode(): TseMode

    /** Gets the default currency code (ISO 4217). */
    suspend fun getDefaultCurrency(): String

    /** Gets a cached setting string synchronously (non-suspend). */
    fun getCachedSettingString(key: ConfigKey): String?

    /** Gets the voucher system configuration as JSON element. */
    suspend fun getVoucherConfig(): JsonElement

    /** Gets the VAT mapping configuration as JSON element. */
    suspend fun getVatMapping(): JsonElement

    /**
     * Decodes the stored JSON object of [key] with [serializer] using the manager's own `Json`.
     *
     * Returns null when no object is stored for the key. A stored object that does not decode
     * throws: the caller owns the fallback policy (the DATEV and AR numbering extensions in
     * accounting each keep their own).
     */
    suspend fun <T> getDecodedSetting(
        key: ConfigKey,
        serializer: KSerializer<T>,
    ): T?

    /**
     * The cash book that receives the daily takings when a Z-Bon is created, or null when the
     * automatic Geldtransit is switched off (the default).
     *
     * Null rather than a fallback book: inferring "the only non-fiscal book" would make the Z-Bon
     * silently stop booking the transfer the moment a second book is created.
     */
    suspend fun getCashBookTransferTargetId(): Long?

    /**
     * How many days back a new Kassenbuch entry's Beleg date may lie — the shop's setting, or
     * `KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS` when unset or out of range.
     */
    suspend fun getKassenbuchMaxBelegAgeDays(): Int

    /** Gets whether the printer configuration is currently enabled. */
    suspend fun isPrinterSettingsEnabled(): Boolean

    /** Gets whether ZVT card terminal integration is enabled. */
    suspend fun isZvtEnabled(): Boolean

    /** Gets whether any cashier can create bills for any table session. */
    suspend fun isAllowCrossEmployeeBilling(): Boolean

    /** Gets whether round-robin employee assignment is enabled for table session opens. */
    suspend fun isRoundRobinAssignment(): Boolean

    /**
     * Gets the terminal roles that may run as browser stations. Fails closed: a missing or
     * unreadable setting is the empty set.
     */
    suspend fun getBrowserStationRoles(): Set<TerminalRole>

    /** The stored API-docs switch (default false). Effective state also depends on [ApiDocsPolicy]. */
    suspend fun isApiDocsSettingEnabled(): Boolean

    /** The server-wide default page for `/`; [StartPageTarget.NONE] when unset or unreadable. */
    suspend fun getStartPageDefault(): StartPageTarget

    /** Gets the configured server port. */
    suspend fun getServerPort(): Int

    /** Gets the configured document storage path. */
    suspend fun getDocumentStoragePath(): String

    /** Gets the list of additional custom document types. */
    suspend fun getAdditionalDocumentTypes(): List<String>

    /** Gets the default backup path from backup configuration. */
    suspend fun getDefaultBackupPath(): String?

    /** Gets whether automatic backups are enabled. */
    suspend fun getBackupAutoEnabled(): Boolean

    /** Gets the cadence at which automatic backups run. */
    suspend fun getBackupCadence(): BackupRestoreDto.BackupCadence

    /** Gets the number of backups to retain before pruning. */
    suspend fun getBackupRetentionCount(): Int

    /** Gets the hour of day (0..23) at which automatic backups run. */
    suspend fun getBackupRunHour(): Int

    /** Gets the timestamp of the last successful backup (ISO-8601), or null if never. */
    suspend fun getLastBackupAt(): String?

    /** The second backup target, or null when none is configured. */
    suspend fun getBackupCopyTarget(): BackupRestoreDto.BackupCopyTargetDto? = null

    /** When the last copy was verified (ISO-8601), or null. */
    suspend fun getLastBackupCopyAt(): String? = null

    /** Outcome of the last copy attempt, or null if none yet. */
    suspend fun getLastBackupCopyStatus(): BackupCopyStatus? = null

    /** Short reason of the last failed copy, or null. */
    suspend fun getLastBackupCopyError(): String? = null

    /** Archives kept on the second target (default 7). */
    suspend fun getBackupCopyRetentionCount(): Int = 7

    /**
     * Gets the maximum allowed backup upload size in bytes.
     *
     * @return Max upload bytes, default 2 GB if not configured
     */
    suspend fun getBackupMaxUploadBytes(): Long

    /** Gets whether automatic DATEV exports are enabled. */
    suspend fun getDatevAutoEnabled(): Boolean

    /** Gets the per-type cadence for DATEV Kassenbuch exports. */
    suspend fun getDatevKassenbuchCadence(): DatevAutoCadence

    /** Gets the per-type cadence for DATEV AP-Ledger exports. */
    suspend fun getDatevApLedgerCadence(): DatevAutoCadence

    /** Gets the per-type cadence for DATEV Buchungsstapel exports. */
    suspend fun getDatevBuchungsstapelCadence(): DatevAutoCadence

    /** Gets the hour of day (0..23) at which automatic DATEV exports run. */
    suspend fun getDatevRunHour(): Int

    /** Gets the DATEV XML Kassen-Name (cash register name). */
    suspend fun getDatevXmlKassenName(): String

    /** Gets the DATEV XML export storage path. */
    suspend fun getDatevXmlExportPath(): String

    /**
     * The restored paths still awaiting an administrator's review after a backup restore, keyed by
     * setting and mapped to the restored (foreign) path. Empty in the normal case. While a setting
     * is listed, every file write to its path is paused.
     *
     * Defaults to empty so test doubles that do not model a restore need not implement it.
     */
    suspend fun getRestoredPathsPendingReview(): Map<RestoredPathSetting, String> = emptyMap()

    /** Gets the DATEV XML Beraternummer (consultant number). */
    suspend fun getDatevXmlConsultantNumber(): String?

    /** Gets the DATEV XML Mandantennummer (client number). */
    suspend fun getDatevXmlClientNumber(): String?

    /** The Sammeldebitor Personenkonto for AR export, or null when unset (slice 4d). */
    suspend fun getDatevArSammeldebitor(): String?

    /** Gets the DATEV AP export mode (MINIMAL or BEST_EFFORT). */
    suspend fun getDatevApExportMode(): String

    /** Gets the DATEV export cadence (WEEKLY, MONTHLY, or MANUAL). */
    suspend fun getDatevExportCadence(): DatevExportCadence

    /** Gets the TSE configuration settings. */
    suspend fun getTseConfig(): TseConfigDto.TseConfig

    /** Sets the TSE configuration settings. */
    suspend fun setTseConfig(config: TseConfigDto.TseConfig)

    /** Forces a refresh of the cached configuration from the database. */
    suspend fun refreshConfiguration(): Boolean

    /** Gets the last-assigned employee id for the round-robin rotation cursor, or null if none. */
    suspend fun getOrderRotationCursor(): UUID?

    /**
     * Reads the round-robin rotation cursor without locking the row, creating it or writing anything.
     * For display only (the "next in rotation" indicator): a pick must use [getOrderRotationCursor].
     */
    suspend fun peekOrderRotationCursor(): UUID?

    /** Persists the round-robin rotation cursor. Pass null to reset. */
    suspend fun setOrderRotationCursor(employeeId: UUID?)

    /** Session-policy config: daily forced re-login + manual panic cutoff. */
    data class TokenSessionPolicy(
        val dailyResetEnabled: Boolean = true,
        val dailyResetTime: String = "04:00",
        val manualCutoffAt: Long? = null, // epoch seconds
    )

    /** Reads the current session policy (cached, falls back to defaults). */
    suspend fun getTokenSessionPolicy(): TokenSessionPolicy

    /** Persists the daily-reset portion of the session policy (keeps manualCutoffAt). */
    suspend fun setTokenDailyReset(
        enabled: Boolean,
        resetTime: String,
    )

    /** Panic action: sets manualCutoffAt to now and busts the cache immediately. */
    suspend fun triggerManualSessionCutoff()

    /**
     * Effective shop-wide session cutoff in epoch seconds (cache-based, non-suspend, hot-path safe).
     * A token is valid only if its issue time >= this value. Returns 0 when no policy/cache is available.
     */
    fun effectiveSessionCutoffEpochSeconds(): Long
}
