package org.example.project.features.setup.domain.repository

import org.example.project.features.setup.database.tables.ConfigKey

/**
 * Hook for features that react to configuration changes. Contributors run in the caller's
 * transaction, at the point the configuration repository writes a setting or seeds defaults.
 */
interface ConfigurationContributor {
    /** Called before a setting is stored; [raw] is the value's string form. */
    fun onSettingWritten(
        key: ConfigKey,
        raw: String,
    ) {}

    /** Called after the default settings of [shopId] have been seeded. */
    fun afterDefaultsInitialized(shopId: Long) {}
}
