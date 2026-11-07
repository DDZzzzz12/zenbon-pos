package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.ServerUrlVerdict

/** Messages for the HTTPS-only server-URL rule (see ServerUrlPolicy). */
object ServerUrlKeys {
    val insecure = StringKey("server_url.insecure")
    val malformed = StringKey("server_url.malformed")
    val insecureTitle = StringKey("server_url.insecure_title")

    /** `{0}` is the stored plain-HTTP address. */
    val insecureSubtitle = StringKey("server_url.insecure_subtitle")
    val saveAndRetry = StringKey("server_url.save_and_retry")

    /** The message for [verdict], or null when the URL may be stored. */
    fun errorFor(verdict: ServerUrlVerdict): StringKey? =
        when (verdict) {
            ServerUrlVerdict.ACCEPTED -> null
            ServerUrlVerdict.INSECURE_REMOTE -> insecure
            ServerUrlVerdict.MALFORMED -> malformed
        }
}
