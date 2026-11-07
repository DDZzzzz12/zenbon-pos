package org.example.project.admin.features.settings.presentation

import org.example.project.admin.core.presentation.navigation.SettingsRoute

/**
 * Resolves a [SettingsRoute]'s serialized section name to its enum entry. An unknown value — a
 * bookmark from before an entry was renamed or promoted out of Einstellungen — degrades to the
 * first section instead of throwing.
 */
fun SettingsRoute.toSection(): SettingsSection = SettingsSection.entries.firstOrNull { it.name == section } ?: SettingsSection.BUSINESS_RULES
