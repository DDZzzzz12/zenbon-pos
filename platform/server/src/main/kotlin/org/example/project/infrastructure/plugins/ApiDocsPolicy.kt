package org.example.project.infrastructure.plugins

import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.security.JwtConfig
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.config.YamlConfigLoader

/**
 * Decides whether the API documentation is served. Precedence: the server configuration
 * (`api.docs.enabled` / `POS_API_DOCS`) > development mode > the Web-Admin switch (default off).
 */
object ApiDocsPolicy {
    data class State(
        val enabled: Boolean,
        val lockedByServerConfig: Boolean,
    )

    private const val OVERRIDE_PATH = "api.docs.enabled"

    /** Read once: the server configuration cannot change while the process runs. */
    @OptIn(TestOnlyApi::class)
    private val serverOverride: Boolean? by lazy { readServerOverride() }

    suspend fun current(configManager: IShopConfigurationManager): State =
        resolve(
            override = serverOverride,
            devTooling = JwtConfig.devToolingEnabled,
            setting = configManager.isApiDocsSettingEnabled(),
        )

    fun resolve(
        override: Boolean?,
        devTooling: Boolean,
        setting: Boolean,
    ): State =
        when {
            override != null -> State(enabled = override, lockedByServerConfig = true)
            devTooling -> State(enabled = true, lockedByServerConfig = false)
            else -> State(enabled = setting, lockedByServerConfig = false)
        }

    fun parseOverride(raw: String?): Boolean? =
        when (raw?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** Absent property (env var unset) and blank/unparseable values all mean "no override". */
    @TestOnlyApi
    fun readServerOverride(): Boolean? {
        val config = YamlConfigLoader.load()
        if (!config.hasPath(OVERRIDE_PATH)) return null
        return parseOverride(config.getString(OVERRIDE_PATH))
    }
}
