package org.example.project.features.zBon.domain

import org.example.project.features.backup.domain.service.PostRestoreHook
import org.example.project.features.setup.data.repository.ConfigurationRepository

/**
 * Boot-time [KassenSwVersionSync] after a restore into a running server: the archive carries the
 * version of the installation that wrote it, and there is no boot after the restore to rewrite it.
 */
class KassenSwVersionPostRestoreHook(
    private val configRepository: ConfigurationRepository,
) : PostRestoreHook {
    override suspend fun afterRestoreCommitted() {
        KassenSwVersionSync(configRepository).syncToRunningBinary()
    }
}
