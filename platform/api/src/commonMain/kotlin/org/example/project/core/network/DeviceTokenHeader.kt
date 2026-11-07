package org.example.project.core.network

import io.ktor.client.plugins.api.createClientPlugin
import org.example.project.core.device.DeviceTokenProvider
import org.example.project.core.utils.DeviceIdentifiers

/**
 * Attaches the paired device token as `X-Device-Token` to every request that does not set the header itself.
 *
 * A plugin rather than a line in `defaultRequest`, because `defaultRequest` cannot suspend and the token may first have
 * to be loaded: [DeviceTokenProvider.currentToken] reads the persisted token when none is held, and that read opens a
 * sealed value on the IO dispatcher. No header is attached while the device is unpaired.
 */
internal val DeviceTokenHeader =
    createClientPlugin("DeviceTokenHeader") {
        onRequest { request, _ ->
            if (request.headers.contains(DeviceIdentifiers.DEVICE_TOKEN_HEADER)) return@onRequest
            val token = DeviceTokenProvider.currentToken() ?: return@onRequest
            request.headers.append(DeviceIdentifiers.DEVICE_TOKEN_HEADER, token)
        }
    }
