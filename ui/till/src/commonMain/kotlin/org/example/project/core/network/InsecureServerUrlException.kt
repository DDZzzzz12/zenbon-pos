package org.example.project.core.network

import kotlinx.io.IOException
import org.example.project.core.utils.ServerUrlVerdict

/**
 * [ServerUrlGuard] refused to send a request whose URL [ServerUrlPolicy] did not accept — either
 * [ServerUrlVerdict.INSECURE_REMOTE] (plain HTTP to a non-loopback host) or
 * [ServerUrlVerdict.MALFORMED] (e.g. credentials embedded in the URL), so the message names the
 * verdict rather than assuming "not HTTPS", which is wrong for the malformed case.
 *
 * An [IOException] on purpose: for every caller this is a transport failure — nothing reached a
 * server — so `apiCall` wraps it in `NetworkApiException` and `runCatchingApi(...).onError` yields
 * `UiError.Network`, the same path an unreachable server takes. The URL is left out of the message
 * because messages end up in logs and client-error reports, and a MALFORMED verdict can mean the
 * URL carries credentials.
 */
class InsecureServerUrlException(
    val verdict: ServerUrlVerdict,
) : IOException("Refusing to send to a server URL that ServerUrlPolicy rejects ($verdict)")
