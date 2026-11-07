package org.example.project.core.auth

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.data.database.KeyValueStore
import org.example.project.core.data.database.SyncDataManager
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.network.defaultApiBaseUrl
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.features.settings.domain.enums.ThemeMode

class SettingManager(
    private val keyValueStore: KeyValueStore,
    private val syncDataManager: SyncDataManager? = null,
) {
    private val logger = Logger.withTag("SessionManager")

    companion object {
        private const val API_URL = "api_base_url"

        private const val THEME_MODE = "theme_mode"

        private const val AUTO_RECONNECT = "auto_reconnect"

        private const val TABLE_PLAN_MODE = "table_plan_mode"

        private const val VOUCHER_SYSTEM_ENABLED = "voucher_system_enabled"

        private const val VOUCHER_SOURCES = "voucher_sources"

        private const val CURRENCY = "currency"

        private const val UI_DENSITY = "ui_density"

        private const val RESTAURANT_MODE = "restaurant_mode"

        private const val SCROLL_SENSITIVITY = "scroll_sensitivity"

        private const val AUTO_START_REMOTE_SERVER = "auto_start_remote_server"

        private const val LAST_SYNCED_AT = "lastSyncedAt"

        private const val SHOP_LOGO_URL = "shop_logo_url"

        private const val EMPLOYEE_SELECTION_ON_FREE_TABLE = "employee_selection_on_free_table"

        private const val ALLOW_CROSS_EMPLOYEE_BILLING = "allow_cross_employee_billing"

        private const val ROUND_ROBIN_ASSIGNMENT = "round_robin_assignment"

        private const val KASSENBUCH_MAX_BELEG_AGE_DAYS = "kassenbuch_max_beleg_age_days"
    }

    // JSON instance for serialization
    private val json = Json { ignoreUnknownKeys = true }

    // Flow for dark mode preference
    private val _themeMode = MutableStateFlow<ThemeMode>(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _autoReconnect = MutableStateFlow(false)
    val autoReconnect: Flow<Boolean> = _autoReconnect.asStateFlow()

    private val _tablePlanMode = MutableStateFlow(false)
    val tablePlanMode: Flow<Boolean> = _tablePlanMode.asStateFlow()

    private val _voucherSystemEnabled = MutableStateFlow(false)
    private val _voucherSources = MutableStateFlow<List<VoucherSource>>(emptyList())
    val voucherEnabled: StateFlow<Boolean> = _voucherSystemEnabled.asStateFlow()
    val voucherSources: StateFlow<List<VoucherSource>> = _voucherSources.asStateFlow()

    private val _defaultCurrency = MutableStateFlow<String>("EUR")
    val defaultCurrency: StateFlow<String> = _defaultCurrency.asStateFlow()

    // Restaurant mode: table management and floor plans enabled
    private val _restaurantMode = MutableStateFlow(true)
    val restaurantMode: Flow<Boolean> = _restaurantMode.asStateFlow()

    // UI Density for desktop scaling (1.0 = 100%, range 0.75 - 2.0)
    private val _uiDensity = MutableStateFlow(1.0f)
    val uiDensity: StateFlow<Float> = _uiDensity.asStateFlow()

    // Scroll sensitivity for desktop touchscreen dampening (0.1 - 1.0, default 0.3)
    private val _scrollSensitivity = MutableStateFlow(0.3f)
    val scrollSensitivity: StateFlow<Float> = _scrollSensitivity.asStateFlow()

    // Auto-start remote ordering server after login
    private val _autoStartRemoteServer = MutableStateFlow(false)
    val autoStartRemoteServer: StateFlow<Boolean> = _autoStartRemoteServer.asStateFlow()

    // Shop logo URL path (relative, needs baseUrl to resolve)
    private val _shopLogoUrl = MutableStateFlow<String?>(null)
    val shopLogoUrl: StateFlow<String?> = _shopLogoUrl.asStateFlow()

    // Order rule: require employee selection when opening a free table
    private val _employeeSelectionOnFreeTable = MutableStateFlow(false)
    val employeeSelectionOnFreeTable: StateFlow<Boolean> = _employeeSelectionOnFreeTable.asStateFlow()

    // Order rule: use round-robin auto-assignment instead of manual employee selection
    private val _roundRobinAssignment = MutableStateFlow(false)
    val roundRobinAssignment: StateFlow<Boolean> = _roundRobinAssignment.asStateFlow()

    // Billing rule: allow any cashier to bill any table session
    private val _allowCrossEmployeeBilling = MutableStateFlow(false)
    val allowCrossEmployeeBilling: StateFlow<Boolean> = _allowCrossEmployeeBilling.asStateFlow()

    // Kassenbuch rule: how many days back a new entry's Beleg date may lie (server-authoritative;
    // the entry form bounds its date picker with it).
    private val _kassenbuchMaxBelegAgeDays = MutableStateFlow(KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS)
    val kassenbuchMaxBelegAgeDays: StateFlow<Int> = _kassenbuchMaxBelegAgeDays.asStateFlow()

    init {
        loadThemeMode()
        getApiBaseUrl()
        loadAutoStartRemoteServer()
        loadAllowCrossEmployeeBilling()
        loadKassenbuchMaxBelegAgeDays()
        // Seed every persisted setting from the (synchronous) key-value store at construction,
        // so the first composition after login already reflects saved values. Otherwise each flow
        // flips from its default to its persisted value only when loadSetting() runs post-sync,
        // re-laying out the already-visible dashboard (grid→floor-plan, UI-density rescale, etc.)
        // and dismissing transient dialogs. The post-sync loadSetting() then just refreshes any
        // values the server changed during sync.
        loadSetting()
    }

    fun loadSetting() {
        loadAutoReconnect()
        loadTablePlanMode()
        isVoucherSystemEnabled()
        getDefaultCurrency()
        loadUiDensity()
        loadScrollSensitivity()
        loadRestaurantMode()
        loadAutoStartRemoteServer()
        loadShopLogoUrl()
        loadEmployeeSelectionOnFreeTable()
        loadRoundRobinAssignment()
    }

    fun getDefaultCurrency() {
        _defaultCurrency.value = keyValueStore.getString(CURRENCY) ?: "EUR"
    }

    fun setDefaultCurrency(currency: String) {
        keyValueStore.setString(CURRENCY, currency)
        _defaultCurrency.value = currency
    }

    /**
     * Sets the UI density scale factor for desktop.
     * @param density Scale factor (0.75 - 2.0, where 1.0 = 100%)
     */
    fun setUiDensity(density: Float) {
        val clampedDensity = density.coerceIn(1.0f, 5.0f)
        keyValueStore.setString(UI_DENSITY, clampedDensity.toString())
        _uiDensity.value = clampedDensity
    }

    /**
     * Loads UI density from preferences.
     */
    fun loadUiDensity() {
        val density = keyValueStore.getString(UI_DENSITY)?.toFloatOrNull() ?: 1.0f
        _uiDensity.value = density.coerceIn(1.0f, 5.0f)
    }

    /**
     * Sets the scroll sensitivity factor for desktop touchscreen dampening.
     * @param value Sensitivity factor (0.1 - 1.0, where 0.3 = 30% of original speed)
     */
    fun setScrollSensitivity(value: Float) {
        val clamped = value.coerceIn(0.1f, 1.0f)
        keyValueStore.setString(SCROLL_SENSITIVITY, clamped.toString())
        _scrollSensitivity.value = clamped
    }

    /**
     * Loads scroll sensitivity from preferences.
     */
    fun loadScrollSensitivity() {
        val sensitivity = keyValueStore.getString(SCROLL_SENSITIVITY)?.toFloatOrNull() ?: 0.3f
        _scrollSensitivity.value = sensitivity.coerceIn(0.1f, 1.0f)
    }

    fun setRestaurantMode(bool: Boolean) {
        keyValueStore.setString(RESTAURANT_MODE, bool.toString())
        _restaurantMode.value = bool
    }

    fun loadRestaurantMode() {
        val flag = keyValueStore.getString(RESTAURANT_MODE)?.toBoolean() ?: true
        _restaurantMode.value = flag
    }

    fun setAutoStartRemoteServer(enabled: Boolean) {
        keyValueStore.setString(AUTO_START_REMOTE_SERVER, enabled.toString())
        _autoStartRemoteServer.value = enabled
    }

    fun loadAutoStartRemoteServer() {
        val flag = keyValueStore.getString(AUTO_START_REMOTE_SERVER)?.toBoolean() ?: false
        _autoStartRemoteServer.value = flag
    }

    fun setShopLogoUrl(url: String?) {
        if (url.isNullOrBlank()) {
            keyValueStore.setString(SHOP_LOGO_URL, "")
            _shopLogoUrl.value = null
        } else {
            keyValueStore.setString(SHOP_LOGO_URL, url)
            _shopLogoUrl.value = url
        }
    }

    fun loadShopLogoUrl() {
        val url = keyValueStore.getString(SHOP_LOGO_URL)
        _shopLogoUrl.value = if (url.isNullOrBlank()) null else url
    }

    fun setEmployeeSelectionOnFreeTable(enabled: Boolean) {
        keyValueStore.setString(EMPLOYEE_SELECTION_ON_FREE_TABLE, enabled.toString())
        _employeeSelectionOnFreeTable.value = enabled
    }

    fun loadEmployeeSelectionOnFreeTable() {
        val flag = keyValueStore.getString(EMPLOYEE_SELECTION_ON_FREE_TABLE)?.toBoolean() ?: false
        _employeeSelectionOnFreeTable.value = flag
    }

    fun setRoundRobinAssignment(enabled: Boolean) {
        keyValueStore.setString(ROUND_ROBIN_ASSIGNMENT, enabled.toString())
        _roundRobinAssignment.value = enabled
    }

    fun loadRoundRobinAssignment() {
        val flag = keyValueStore.getString(ROUND_ROBIN_ASSIGNMENT)?.toBoolean() ?: false
        _roundRobinAssignment.value = flag
    }

    fun setAllowCrossEmployeeBilling(enabled: Boolean) {
        keyValueStore.setString(ALLOW_CROSS_EMPLOYEE_BILLING, enabled.toString())
        _allowCrossEmployeeBilling.value = enabled
    }

    fun loadAllowCrossEmployeeBilling() {
        val flag = keyValueStore.getString(ALLOW_CROSS_EMPLOYEE_BILLING)?.toBoolean() ?: false
        _allowCrossEmployeeBilling.value = flag
    }

    fun setKassenbuchMaxBelegAgeDays(days: Int) {
        val effective = KassenbuchBelegDateRule.effectiveMaxAgeDays(days)
        keyValueStore.setString(KASSENBUCH_MAX_BELEG_AGE_DAYS, effective.toString())
        _kassenbuchMaxBelegAgeDays.value = effective
    }

    fun loadKassenbuchMaxBelegAgeDays() {
        _kassenbuchMaxBelegAgeDays.value =
            KassenbuchBelegDateRule.effectiveMaxAgeDays(keyValueStore.getString(KASSENBUCH_MAX_BELEG_AGE_DAYS)?.toIntOrNull())
    }

    fun setTablePlanMode(bool: Boolean) {
        if (bool) {
            keyValueStore.setString(TABLE_PLAN_MODE, "true")
            _tablePlanMode.value = true
        } else {
            keyValueStore.setString(TABLE_PLAN_MODE, "false")
            _tablePlanMode.value = false
        }
    }

    fun loadTablePlanMode() {
        val flag = keyValueStore.getString(TABLE_PLAN_MODE)?.toBoolean() ?: false
        _tablePlanMode.value = flag
    }

    fun setAutoReconnect(bool: Boolean) {
        if (bool) {
            keyValueStore.setString(AUTO_RECONNECT, "true")
            _autoReconnect.value = true
        } else {
            keyValueStore.setString(AUTO_RECONNECT, "false")
            _autoReconnect.value = false
        }
    }

    fun loadAutoReconnect() {
        val flag = keyValueStore.getString(AUTO_RECONNECT)?.toBoolean() ?: false
        _autoReconnect.value = flag
    }

    fun setThemeMode(themeMode: ThemeMode) {
        keyValueStore.setString(THEME_MODE, themeMode.name)
        _themeMode.value = themeMode
    }

    fun loadThemeMode() {
        val themeMode = keyValueStore.getString(THEME_MODE)
        if (themeMode != null) {
            _themeMode.value = ThemeMode.valueOf(themeMode)
        } else {
            _themeMode.value = ThemeMode.SYSTEM
        }
    }

    fun clearSyncedData() {
        syncDataManager?.clearAllSyncData()
        // Reset last-synced timestamp to epoch so next sync fetches everything
        keyValueStore.setString(LAST_SYNCED_AT, LocalDateTime(2000, 1, 1, 0, 0, 0, 0).toString())
        logger.i { "Cleared sync data" }
    }

    fun setApiBaseUrl(url: String): Boolean {
        keyValueStore.setString(API_URL, url)
        _url.value = url
        clearSyncedData()
        return true
    }

    fun getApiBaseUrl() {
        // Same default as LocalDatabaseManager.getApiBaseUrl -- two copies of this literal is how
        // they drift. See [defaultApiBaseUrl]: on wasm it is the page origin, not localhost.
        _url.value = keyValueStore.getString(API_URL) ?: defaultApiBaseUrl()
    }

    /**
     * Stores voucher sources configuration as JSON.
     *
     * Serializes the list of voucher sources to JSON and stores it in preferences
     * for persistence across application restarts.
     *
     * @param voucherSources List of voucher source configurations to store
     */
    fun setVoucherSource(voucherSources: List<VoucherSource>) {
        val jsonString = json.encodeToString(voucherSources)
        keyValueStore.setString(VOUCHER_SOURCES, jsonString)
        _voucherSources.value = voucherSources
    }

    /**
     * Loads voucher sources configuration from JSON.
     *
     * Deserializes the stored JSON string back to a list of voucher sources.
     * Returns empty list if no configuration is stored.
     *
     * @return List of stored voucher source configurations, or empty list if none
     */
    fun getVoucherSources(): List<VoucherSource> {
        if (_voucherSources.value.isNotEmpty()) return _voucherSources.value
        val jsonString = keyValueStore.getString(VOUCHER_SOURCES) ?: return emptyList()
        return try {
            json.decodeFromString<List<VoucherSource>>(jsonString)
        } catch (e: Exception) {
            // Return empty list if deserialization fails
            emptyList()
        }
    }

    /**
     * Stores whether voucher system is enabled.
     *
     * Caches the voucher configuration status from server for quick access.
     *
     * @param enabled Whether voucher system is enabled
     */
    fun setVoucherSystemEnabled(enabled: Boolean) {
        keyValueStore.setString(
            VOUCHER_SYSTEM_ENABLED,
            enabled.toString(),
        )
        _voucherSystemEnabled.value = enabled
    }

    /**
     * Checks if voucher system is enabled.
     *
     * Returns cached value from local preferences. Should be refreshed
     * from server configuration periodically.
     *
     * @return True if voucher system is enabled, false otherwise (defaults to false if not set)
     */
    fun isVoucherSystemEnabled() {
        keyValueStore.getString(VOUCHER_SYSTEM_ENABLED)?.toBoolean()?.let { _voucherSystemEnabled.value = it }
    }

    /**
     * Loads voucher configuration from local preferences.
     *
     * Constructs VoucherConfig from stored voucher sources and system enabled state.
     * Falls back to default configuration if no sources are stored.
     *
     * @return VoucherConfig with stored sources or default configuration
     */
    fun loadVoucherConfig(): VoucherConfig {
        val storedSources = getVoucherSources()
        val isEnabled = keyValueStore.getString(VOUCHER_SYSTEM_ENABLED)?.toBoolean() ?: false

        // If no sources stored, return default config
        if (storedSources.isEmpty()) {
            return VoucherConfig.getDefault()
        }

        return VoucherConfig(
            enabled = isEnabled,
            sources = storedSources,
        )
    }
}
