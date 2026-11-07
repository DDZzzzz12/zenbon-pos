package org.example.project.admin.core.network

import io.ktor.client.plugins.logging.Logger
import co.touchlab.kermit.Logger as KermitLogger

/** Routes Ktor's HTTP logging to the browser console via Kermit. */
class KermitKtorLogger(
    private val tag: String = "http",
) : Logger {
    override fun log(message: String) = KermitLogger.withTag(tag).d { message }
}
