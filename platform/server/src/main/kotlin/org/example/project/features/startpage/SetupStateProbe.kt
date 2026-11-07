package org.example.project.features.startpage

import org.example.project.features.setup.domain.service.InitialSetupService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Is this server set up?" without paying for `checkInitializationStatus()` (several count queries) on every
 * `GET /`. Once initialised, a server stays initialised, so a positive answer is remembered; a negative one is
 * re-checked on the next request (setup may just have finished). One instance per application — created in
 * [startPageRoutes] — so the remembered answer never outlives the application that observed it.
 */
class SetupStateProbe(
    private val service: InitialSetupService,
) {
    private val initialized = AtomicBoolean(false)

    suspend fun isInitialized(): Boolean {
        if (initialized.get()) return true
        val now = service.checkInitializationStatus().isInitialized
        if (now) initialized.set(true)
        return now
    }
}
