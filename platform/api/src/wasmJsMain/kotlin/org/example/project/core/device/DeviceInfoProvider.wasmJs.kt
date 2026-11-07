package org.example.project.core.device

import kotlinx.browser.localStorage
import org.example.project.core.utils.DeviceIdentifiers

/**
 * Which wasm client this browser page is running, which decides the device identity it presents.
 *
 * Both clients are Compose/wasm pages served from the same pos-server origin, so they share one
 * `localStorage`. They must therefore use different storage keys as well as different id prefixes —
 * otherwise opening the preview would silently adopt the admin console's device id, and the server
 * would see one device that is both a back-office tool and a till.
 */
enum class WasmClientKind {
    /** The back-office admin console (adminApp). Never a terminal. */
    ADMIN_CONSOLE,

    /** The browser preview of the till (composeApp). Registers as a terminal. */
    TILL_PREVIEW,
}

/**
 * Browser implementation of [DeviceInfoProvider].
 *
 * The id is a random UUID behind a per-client prefix, persisted in `localStorage` and cached in
 * memory; it survives reloads and resets when the user clears site data or switches browser/profile.
 * Persistence matters because the server treats the device id as an identity — a fresh id per reload
 * would look like a new terminal on every refresh.
 *
 * Defaults to [WasmClientKind.ADMIN_CONSOLE] so adminApp needs no change and already-deployed admin
 * browsers keep their stored id under the original key.
 */
actual object DeviceInfoProvider {
    private const val ADMIN_STORAGE_KEY = "admin_device_id"
    private const val PREVIEW_STORAGE_KEY = "pos_preview_device_id"

    private var clientKind = WasmClientKind.ADMIN_CONSOLE
    private var cachedDeviceId: String? = null

    /**
     * Declares which client this page is. Call once at startup, before anything reads
     * [getDeviceId] — composeApp's `main()` does this; adminApp relies on the default.
     */
    fun configureClientKind(kind: WasmClientKind) {
        if (kind == clientKind) return
        clientKind = kind
        cachedDeviceId = null
    }

    private val storageKey: String
        get() =
            when (clientKind) {
                WasmClientKind.ADMIN_CONSOLE -> ADMIN_STORAGE_KEY
                WasmClientKind.TILL_PREVIEW -> PREVIEW_STORAGE_KEY
            }

    private val idPrefix: String
        get() =
            when (clientKind) {
                WasmClientKind.ADMIN_CONSOLE -> DeviceIdentifiers.ADMIN_WEB_PREFIX
                WasmClientKind.TILL_PREVIEW -> DeviceIdentifiers.POS_PREVIEW_PREFIX
            }

    actual fun getDeviceId(): String {
        cachedDeviceId?.let { return it }
        val key = storageKey
        val existing = localStorage.getItem(key)
        val id =
            if (existing.isNullOrBlank()) {
                (idPrefix + randomUuid()).also { localStorage.setItem(key, it) }
            } else {
                existing
            }
        cachedDeviceId = id
        return id
    }

    actual fun getDeviceName(): String =
        when (clientKind) {
            WasmClientKind.ADMIN_CONSOLE -> "Web Admin"
            WasmClientKind.TILL_PREVIEW -> "POS Browser Preview"
        }

    actual fun getDeviceModel(): String = "Browser"

    actual fun getOsVersion(): String = "Web"

    /** A browser has no OS brand relevant to fiscal attribution; "Web" tags it as browser-hosted. */
    actual fun getPlatformBrand(): String = "Web"
}

/**
 * A random UUID v4 for the device id.
 *
 * `crypto.randomUUID()` exists only in a secure context (https or localhost). Opened over plain
 * http on the shop LAN (e.g. http://192.168.x.y:8080/admin) it is undefined, and calling it threw
 * on startup, so the back office never rendered — a blank canvas in every browser (found on the
 * real-TSE test machine, 2026-10-04). `crypto.getRandomValues` is available in insecure contexts
 * too, so the fallback builds an RFC 4122 v4 UUID from 16 random bytes.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun randomUuid(): String =
    js(
        """((typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
            ? crypto.randomUUID()
            : (function () {
                var b = crypto.getRandomValues(new Uint8Array(16));
                b[6] = (b[6] & 0x0f) | 0x40;
                b[8] = (b[8] & 0x3f) | 0x80;
                var h = Array.prototype.map.call(b, function (x) { return (x + 0x100).toString(16).slice(1); }).join('');
                return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
            })())""",
    )
