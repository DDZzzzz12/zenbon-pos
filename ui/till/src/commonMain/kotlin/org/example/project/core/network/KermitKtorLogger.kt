package org.example.project.core.network

import co.touchlab.kermit.Logger as KermitLogger
import io.ktor.client.plugins.logging.Logger as KtorLogger

class KermitKtorLogger(
    private val kermitLogger: KermitLogger,
) : KtorLogger {
    override fun log(message: String) {
        kermitLogger.i { message }
    }
}
