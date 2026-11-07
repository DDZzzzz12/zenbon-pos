package org.example.project.core.browser

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

/** Inline `js(...)` glue only: a wasm library must not depend on a resource file of its own. */
@OptIn(ExperimentalWasmJsInterop::class)
actual object BrowserKiosk {
    actual fun install() = jsInstall()

    actual fun setLeaveGuard(on: Boolean) = jsSetLeaveGuard(on)

    actual fun requestFullscreenOnFirstGesture() = jsRequestFullscreenOnFirstGesture()

    actual fun consumePairingCodeFromUrl(): String? {
        val parsed = parsePairingCode(jsSearch())
        if (parsed.cleanedQuery != jsSearch()) jsReplaceQuery(parsed.cleanedQuery)
        return parsed.code
    }

    actual fun isInsecureOrigin(): Boolean = jsIsInsecureOrigin()
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun jsSearch(): String = js("location.search")

/** Rewrites the address bar in place (no new history entry), keeping path and hash. */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsReplaceQuery(query: String): Unit = js("history.replaceState(history.state, '', location.pathname + query + location.hash)")

/** Pushes a history entry now and again on every back press, so Back never leaves the till. */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsInstall(): Unit =
    js(
        """{
    if (window.__zenbonKioskInstalled) return;
    window.__zenbonKioskInstalled = true;
    history.pushState(null, '', location.href);
    window.addEventListener('popstate', function () { history.pushState(null, '', location.href); });
    document.body.style.overscrollBehavior = 'none';
    document.documentElement.style.overscrollBehavior = 'none';
}""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun jsSetLeaveGuard(on: Boolean): Unit =
    js(
        """{
    if (!window.__zenbonLeaveHandler) {
        window.__zenbonLeaveHandler = function (e) { e.preventDefault(); e.returnValue = ''; return ''; };
    }
    window.removeEventListener('beforeunload', window.__zenbonLeaveHandler);
    if (on) window.addEventListener('beforeunload', window.__zenbonLeaveHandler);
}""",
    )

/** Fullscreen needs a user gesture, so it waits for the first press; a refusal must not break the till. */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsRequestFullscreenOnFirstGesture(): Unit =
    js(
        """{
    if (location.search.indexOf('kiosk=1') < 0) return;
    var once = function () {
        document.removeEventListener('pointerdown', once, true);
        try {
            var p = document.documentElement.requestFullscreen();
            if (p && p.catch) p.catch(function () {});
        } catch (e) {}
    };
    document.addEventListener('pointerdown', once, true);
}""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun jsIsInsecureOrigin(): Boolean =
    js(
        """(location.protocol !== 'https:' &&
        ['localhost', '127.0.0.1', '[::1]'].indexOf(location.hostname) < 0)""",
    )
