package org.example.project.infrastructure.security.rules

import io.ktor.http.HttpMethod
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.AccessLevel.Cap
import org.example.project.infrastructure.security.RuleModule

/**
 * Backup creation, listing and restore — `BackupRestoreRoutes.kt`.
 *
 * `BACKUP_RESTORE` is RESERVED: a restore overwrites the live database **and** the documents
 * directory, so it must never be placeable in a custom group.
 */
object BackupRules : RuleModule {
    override val name = "Backup and restore"

    override val pathPrefixes = listOf("/api/v1/backup")

    override val allowedCapabilities = setOf(Capability.BACKUP_CREATE, Capability.BACKUP_RESTORE)

    override val rules: Map<Pair<String, HttpMethod>, AccessLevel> =
        mapOf(
            ("/api/v1/backup/create" to HttpMethod.Post) to Cap(Capability.BACKUP_CREATE),
            ("/api/v1/backup/restore" to HttpMethod.Post) to Cap(Capability.BACKUP_RESTORE),
            ("/api/v1/backup/config" to HttpMethod.Get) to Cap(Capability.BACKUP_CREATE),
            ("/api/v1/backup/config" to HttpMethod.Put) to Cap(Capability.BACKUP_CREATE),
            ("/api/v1/backup/create-to-server" to HttpMethod.Post) to Cap(Capability.BACKUP_CREATE),
            ("/api/v1/backup/restore-from-server" to HttpMethod.Post) to Cap(Capability.BACKUP_RESTORE),
            ("/api/v1/backup/directories" to HttpMethod.Get) to Cap(Capability.BACKUP_CREATE),
            ("/api/v1/backup/list" to HttpMethod.Get) to Cap(Capability.BACKUP_CREATE),
            // Post-restore path review: deciding whether the restored folders of another
            // installation may be written to finishes the restore, so it carries the restore's own
            // (RESERVED) capability rather than the narrower per-folder config capabilities.
            ("/api/v1/backup/restored-paths" to HttpMethod.Get) to Cap(Capability.BACKUP_RESTORE),
            ("/api/v1/backup/restored-paths" to HttpMethod.Post) to Cap(Capability.BACKUP_RESTORE),
        )
}
