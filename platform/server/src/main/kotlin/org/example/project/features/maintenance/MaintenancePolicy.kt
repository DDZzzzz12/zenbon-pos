package org.example.project.features.maintenance

import org.example.project.utils.config.YamlConfigLoader
import org.slf4j.LoggerFactory

/**
 * Process-wide holder for the maintainer settings. They come from the server configuration, which
 * cannot change while the process runs, so they are read once (like `ApiDocsPolicy`'s override).
 *
 * [useForTesting] is the only seam: tests cannot set environment variables in-process.
 */
object MaintenancePolicy {
    private val logger = LoggerFactory.getLogger(MaintenancePolicy::class.java)

    @Volatile
    private var override: MaintenanceConfig? = null

    private val fromYaml: MaintenanceConfig by lazy { MaintenanceConfig.fromYaml(YamlConfigLoader.load()) }

    fun current(): MaintenanceConfig = override ?: fromYaml

    val enabled: Boolean get() = current().enabled

    /** Call at startup: validates the configuration (throws on a malformed hash) and says what it found. */
    fun logStartupState() {
        val config = current()
        if (config.enabled) {
            logger.info("Maintainer account: ENABLED (sessions last {} min)", config.sessionMinutes)
        } else {
            logger.info("Maintainer account: disabled (set MAINTAINER_USERNAME and MAINTAINER_PASSWORD_HASH to enable)")
        }
    }

    /** Tests only. Pass null to restore the real configuration. */
    fun useForTesting(config: MaintenanceConfig?) {
        override = config
    }
}
