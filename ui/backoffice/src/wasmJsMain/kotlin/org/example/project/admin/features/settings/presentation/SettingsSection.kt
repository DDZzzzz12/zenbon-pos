package org.example.project.admin.features.settings.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.IssueReportKeys

/**
 * The sections of the Einstellungen destination, in display order.
 *
 * Set-once configuration only. Vouchers, printers, Kassenmeldung and Datenexport used to live here
 * but are top-level destinations now — they are things you *do*, on their own cadence, not
 * preferences you set at setup.
 *
 * [requiredCapability] narrows a section below the destination's own `CONFIG_WRITE` gate where the
 * section's endpoints demand more. Geschäftsdaten names `SHOP_INFO_MANAGE`, which `ShopInfoRules`
 * puts on every back-office read of `/api/shop-info` — a manager without it was shown an empty form
 * and a 403 toast (QA admin19 NOTE-h). There is no read-only variant to fall back to: the only
 * `SHOP_INFO_READ` route is the till's `/sync`. The server keeps refusing regardless (rule 8).
 */
enum class SettingsSection(
    val labelKey: StringKey,
    val requiredCapability: Capability? = null,
) {
    BUSINESS_RULES(AdminStringKeys.SettingsSectionBusinessRules),
    SHOP_DATA(AdminStringKeys.SettingsSectionShopData, Capability.SHOP_INFO_MANAGE),
    VAT_RATES(AdminStringKeys.SettingsSectionVatRates),
    VAT_MAPPING(AdminStringKeys.SettingsSectionVatMapping),
    PAYMENT_TYPES(AdminStringKeys.SettingsSectionPaymentTypes),
    DOCUMENT_STORAGE(AdminStringKeys.SettingsSectionDocumentStorage),
    BROWSER_STATIONS(AdminStringKeys.SettingsSectionBrowserStations, Capability.CONFIG_WRITE),
    SYSTEM(AdminStringKeys.SettingsSectionSystem, Capability.CONFIG_WRITE),
    OPENING_HOURS(AdminStringKeys.SettingsSectionOpeningHours),
    SECURITY_SESSIONS(AdminStringKeys.SettingsSectionSecuritySessions),

    /** Where "Report a problem" files its issues, and the token — ADMIN only, as `IssueReportRules` requires. */
    ISSUE_REPORTS(IssueReportKeys.settingsSectionTitle, Capability.ISSUE_REPORT_MANAGE),
    CHANGELOG(AdminStringKeys.SettingsSectionChangelog),
    ;

    fun isVisibleTo(policy: AccessPolicy): Boolean = requiredCapability == null || policy.allows(requiredCapability)

    companion object {
        /** The sections [policy] may open, in display order. */
        fun visibleTo(policy: AccessPolicy): List<SettingsSection> = entries.filter { it.isVisibleTo(policy) }

        /**
         * The label the phone top bar shows for the section named [sectionName]. Mirrors what
         * `SettingsScreen` actually renders: an unknown or hidden section falls back to
         * [BUSINESS_RULES], so the title and the content can never disagree.
         */
        fun titleKeyFor(
            sectionName: String?,
            policy: AccessPolicy,
        ): StringKey {
            val section = entries.firstOrNull { it.name == sectionName } ?: return BUSINESS_RULES.labelKey
            return if (section.isVisibleTo(policy)) section.labelKey else BUSINESS_RULES.labelKey
        }
    }
}
