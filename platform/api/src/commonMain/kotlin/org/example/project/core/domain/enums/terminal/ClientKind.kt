package org.example.project.core.domain.enums.terminal

import org.example.project.core.utils.DeviceIdentifiers

/**
 * What kind of client a terminal is paired from. Asserted by the client at pairing, so it is
 * policy and hygiene, not a security boundary — [effective] additionally treats the browser
 * till's `pos-preview-` device ids as BROWSER so a plain claim of NATIVE does not hide one.
 */
enum class ClientKind {
    NATIVE,
    BROWSER,
    ;

    companion object {
        fun fromCode(code: String?): ClientKind = entries.firstOrNull { it.name.equals(code?.trim(), ignoreCase = true) } ?: NATIVE

        fun effective(
            requested: String?,
            deviceId: String?,
        ): ClientKind = if (fromCode(requested) == BROWSER || DeviceIdentifiers.isPosPreviewDevice(deviceId.orEmpty())) BROWSER else NATIVE
    }
}
