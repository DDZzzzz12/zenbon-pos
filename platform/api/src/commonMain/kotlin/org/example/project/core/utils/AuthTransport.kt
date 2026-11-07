package org.example.project.core.utils

/**
 * Selects how the refresh token travels, per request. adminApp sends [HEADER]: [COOKIE]; native
 * clients send nothing and keep the token in the JSON body. Shared so the server and the client can
 * never disagree about the spelling.
 */
object AuthTransport {
    const val HEADER = "X-Auth-Transport"
    const val COOKIE = "cookie"
}
