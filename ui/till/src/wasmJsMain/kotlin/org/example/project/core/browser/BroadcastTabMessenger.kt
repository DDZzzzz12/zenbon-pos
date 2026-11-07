package org.example.project.core.browser

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js

/**
 * [TabMessenger] over a same-origin `BroadcastChannel`. Unlike Web Locks or `crypto.randomUUID`,
 * BroadcastChannel also works on a plain-http LAN origin. A channel never delivers to its own
 * sender, which is what [TabMessenger] promises.
 */
@OptIn(ExperimentalWasmJsInterop::class)
class BroadcastTabMessenger : TabMessenger {
    private val channel: JsAny = openChannel()

    override fun send(msg: String) {
        post(channel, msg)
    }

    override fun onMessage(cb: (String) -> Unit) {
        listen(channel) { data -> cb(data.toString()) }
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun openChannel(): JsAny = js("new BroadcastChannel('zenbon-pos-tab')")

@OptIn(ExperimentalWasmJsInterop::class)
private fun post(
    channel: JsAny,
    msg: String,
): Unit = js("channel.postMessage(msg)")

@OptIn(ExperimentalWasmJsInterop::class)
private fun listen(
    channel: JsAny,
    cb: (JsString) -> Unit,
): Unit = js("channel.onmessage = function (e) { cb(String(e.data)); }")
