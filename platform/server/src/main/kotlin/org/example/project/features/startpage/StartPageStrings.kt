package org.example.project.features.startpage

/** Start-page texts. Kept here, not in the client string files: the server renders this page itself. */
object StartPageStrings {
    const val TITLE = "title"
    const val ADMIN = "admin"
    const val ADMIN_HINT = "admin_hint"
    const val POS_CASHIER = "pos_cashier"
    const val POS_STATION = "pos_station"
    const val POS_BOTH = "pos_both"
    const val POS_HINT = "pos_hint"
    const val MANUAL = "manual"
    const val API_DOCS = "api_docs"
    const val SET_DEFAULT = "set_default"
    const val DEFAULT_BROWSER = "default_browser"
    const val DEFAULT_SERVER = "default_server"
    const val RESET = "reset"
    const val DEFAULT_UNAVAILABLE = "default_unavailable"
    const val VERSION = "version"

    private val de =
        mapOf(
            TITLE to "Zenbon POS",
            ADMIN to "Web-Admin",
            ADMIN_HINT to "Verwaltung, Berichte, Einstellungen",
            POS_CASHIER to "Browser-Kasse",
            POS_STATION to "Bestellstation",
            POS_BOTH to "Browser-Kasse / Bestellstation",
            POS_HINT to "Diesen Browser im Web-Admin unter Geräte → Terminals koppeln oder den Kopplungslink von dort öffnen.",
            MANUAL to "Handbuch",
            API_DOCS to "API-Dokumentation",
            SET_DEFAULT to "Als Standard für diesen Browser",
            DEFAULT_BROWSER to "Standard für diesen Browser: {0}",
            DEFAULT_SERVER to "Standard des Servers: {0}",
            RESET to "zurücksetzen",
            DEFAULT_UNAVAILABLE to "Ihre Standardseite ist derzeit nicht verfügbar.",
            VERSION to "Version {0}",
        )
    private val en =
        mapOf(
            TITLE to "Zenbon POS",
            ADMIN to "Web admin",
            ADMIN_HINT to "Management, reports, settings",
            POS_CASHIER to "Browser till",
            POS_STATION to "Order station",
            POS_BOTH to "Browser till / order station",
            POS_HINT to "Pair this browser in the web admin under Devices → Terminals, or open the pairing link from there.",
            MANUAL to "Manual",
            API_DOCS to "API documentation",
            SET_DEFAULT to "Make default for this browser",
            DEFAULT_BROWSER to "Default for this browser: {0}",
            DEFAULT_SERVER to "Server default: {0}",
            RESET to "reset",
            DEFAULT_UNAVAILABLE to "Your default page is not available right now.",
            VERSION to "Version {0}",
        )
    private val zh =
        mapOf(
            TITLE to "Zenbon POS",
            ADMIN to "网页管理后台",
            ADMIN_HINT to "管理、报表、设置",
            POS_CASHIER to "浏览器收银台",
            POS_STATION to "点单站",
            POS_BOTH to "浏览器收银台 / 点单站",
            POS_HINT to "请在网页管理后台的 设备 → 终端 中配对此浏览器，或从那里打开配对链接。",
            MANUAL to "使用手册",
            API_DOCS to "API 文档",
            SET_DEFAULT to "设为此浏览器的默认页面",
            DEFAULT_BROWSER to "此浏览器的默认页面：{0}",
            DEFAULT_SERVER to "服务器默认页面：{0}",
            RESET to "重置",
            DEFAULT_UNAVAILABLE to "您的默认页面目前不可用。",
            VERSION to "版本 {0}",
        )

    fun of(language: StartPageLanguage): Map<String, String> =
        when (language) {
            StartPageLanguage.DE -> de
            StartPageLanguage.EN -> en
            StartPageLanguage.ZH -> zh
        }
}
