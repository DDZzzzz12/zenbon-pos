package org.example.project.core.browser

/**
 * Result of reading a pairing code out of a URL query string: the [code] (null when absent or
 * malformed) and the [cleanedQuery] to put back in the address bar, with `code` removed and every
 * other parameter (`kiosk=1`) kept. [cleanedQuery] is empty or starts with `?`.
 */
data class PairingCodeFromUrl(
    val code: String?,
    val cleanedQuery: String,
)

private val PAIRING_CODE = Regex("[0-9]{6}")

/** Pure parse of `location.search`. The server issues 6-digit codes, which is also all the field accepts. */
fun parsePairingCode(search: String): PairingCodeFromUrl {
    val params = search.removePrefix("?").split('&').filter { it.isNotEmpty() }
    var code: String? = null
    val kept = mutableListOf<String>()
    for (param in params) {
        if (param.substringBefore('=') == "code") {
            val value = param.substringAfter('=', "")
            if (code == null && PAIRING_CODE.matches(value)) code = value
        } else {
            kept += param
        }
    }
    return PairingCodeFromUrl(code, if (kept.isEmpty()) "" else "?" + kept.joinToString("&"))
}
