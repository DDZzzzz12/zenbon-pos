package org.example.project.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import co.touchlab.kermit.Logger
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.example.project.core.network.HttpCall

/**
 * Fetches the CJK font on demand and exposes it as Compose state.
 *
 * The browser clients (adminApp and the till) bundle no font of their own and rely on Compose/wasm's
 * built-in default, which carries no CJK glyphs — so every Chinese character draws as a tofu box until this loads. The usual web
 * fix does not apply: Compose renders into a `<canvas>` through Skia, which keeps its own font
 * collection and never consults the browser's CSS font registry, so a `<link rel="stylesheet">`
 * would download the font and change nothing. Skia can only draw from bytes handed to it.
 *
 * Loaded lazily rather than bundled: even subset to ~1.6 MB (see below) it is dead weight for the
 * German and English installs that never select Chinese. Served by pos-server (same origin) rather
 * than a CDN, because POS installations frequently run isolated from the internet and a CDN would
 * make Chinese fail silently whenever the line is down.
 *
 * The file is a **GB2312 + app-UI subset** of Noto Sans SC, not the full ~8 MB font — see
 * `static/font/README.md` for the coverage, the 5× size cut, and how to widen it if a rare hanzi
 * (e.g. an uncommon character in a customer name) ever renders as tofu.
 *
 * On failure [family] stays null and the UI keeps rendering tofu rather than crashing — the log
 * says why.
 */
object CjkFontLoader {
    private val log = Logger.withTag("cjk-font")

    /** Same origin as the page; pos-server exposes it via `staticResources("/font", …)`. */
    private const val FONT_PATH = "/font/NotoSansSC-GB2312-v2.otf"

    /** Null until the fetch succeeds. Reading this in composition re-renders when it arrives. */
    var family: FontFamily? by mutableStateOf(null)
        private set

    /** Guards against a second fetch while the first is still in flight. */
    private var loading = false

    /**
     * Idempotent: returns immediately once loaded, and will not start a second concurrent fetch.
     */
    suspend fun load(httpCall: HttpCall) {
        if (family != null || loading) return
        loading = true
        try {
            // The configured server, not a bare path: the till's HttpCall carries a base URL the
            // user can change, while adminApp's is "/" and resolves to the page origin.
            val url = httpCall.getBaseUrl().trimEnd('/') + FONT_PATH
            log.i { "fetching $url" }
            val bytes: ByteArray = httpCall.httpClient.get(url).body()
            family = FontFamily(Font(identity = "NotoSansSC", data = bytes))
            log.i { "loaded ${bytes.size} bytes — CJK text will now render" }
        } catch (e: Throwable) {
            log.e(e) { "failed to load $FONT_PATH — Chinese text will render as tofu" }
        } finally {
            loading = false
        }
    }
}
