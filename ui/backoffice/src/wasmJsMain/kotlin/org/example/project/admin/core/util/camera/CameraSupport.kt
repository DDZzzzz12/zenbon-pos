@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.camera

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Whether the live camera can work here. `getUserMedia` exists only in a secure context, so over
 * `http://<LAN IP>` `navigator.mediaDevices` is undefined and a naive call throws a TypeError (the
 * same trap `crypto.randomUUID` set). Feature-detect, never assume.
 */
enum class CameraSupport {
    Available,
    InsecureContext,
    Unsupported,
    ;

    companion object {
        fun classify(
            isSecureContext: Boolean,
            hasGetUserMedia: Boolean,
        ): CameraSupport =
            when {
                hasGetUserMedia -> Available
                !isSecureContext -> InsecureContext
                else -> Unsupported
            }

        fun detect(): CameraSupport = classify(isSecureContextJs(), hasGetUserMediaJs())
    }
}

private fun isSecureContextJs(): Boolean = js("window.isSecureContext === true")

private fun hasGetUserMediaJs(): Boolean = js("!!(navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function')")
