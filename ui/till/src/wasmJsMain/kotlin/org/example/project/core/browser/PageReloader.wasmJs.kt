package org.example.project.core.browser

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

actual object PageReloader {
    /**
     * The token and session live in localStorage, so they survive the reload. The kiosk leave guard
     * (`beforeunload`) is removed first: with it armed Chrome shows its native "Leave site?" prompt
     * and the unattended reload hangs on it.
     */
    actual fun reload() {
        BrowserKiosk.setLeaveGuard(false)
        jsReload()
    }

    actual fun readLastAutoReload(): Long? = jsReadStamp().takeIf { it > 0 }?.toLong()

    actual fun writeLastAutoReload(epochMillis: Long) = jsWriteStamp(epochMillis.toDouble())
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun jsReload(): Unit = js("window.location.reload()")

/** sessionStorage can throw (blocked storage, private mode); 0 means "unknown". */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsReadStamp(): Double =
    js(
        """{
    try { var v = window.sessionStorage.getItem('zenbon.lastAutoReload'); return v ? Number(v) || 0 : 0; }
    catch (e) { return 0; }
}""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun jsWriteStamp(value: Double): Unit =
    js(
        """{
    try { window.sessionStorage.setItem('zenbon.lastAutoReload', String(value)); } catch (e) {}
}""",
    )
