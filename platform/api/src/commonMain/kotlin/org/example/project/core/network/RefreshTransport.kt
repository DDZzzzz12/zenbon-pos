package org.example.project.core.network

/** How [createPosHttpClient] renews an access token. */
enum class RefreshTransport {
    /** Refresh token held by the client and sent in the JSON body — composeApp. */
    BODY,

    /**
     * Refresh token held by the browser as an HttpOnly cookie the client never sees; requests carry
     * `X-Auth-Transport: cookie` — adminApp.
     */
    COOKIE,
}
