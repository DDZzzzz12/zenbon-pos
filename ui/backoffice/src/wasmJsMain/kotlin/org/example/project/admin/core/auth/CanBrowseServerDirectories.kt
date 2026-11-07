package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Browsing the server's filesystem from the console — the backup and document-storage pickers.
 *
 * `BACKUP_CREATE` is what the server requires on `GET /api/v1/backup/directories`, the endpoint this
 * gate fronts, and it is not in the MANAGER bundle — so this stays admin-only, matching the
 * `role == ADMIN` check it replaces.
 *
 * A separate extension rather than reusing [canAdministerTse]: the names outlive whoever wrote them.
 *
 * NOT `SYSTEM_CONFIG`, which looks apt and is wrong: despite being `Assignability.RESERVED` it sits
 * in the MANAGER bundle, so it would have widened this gate to every manager. RESERVED constrains
 * who may be GRANTED a capability by editing a group, not who already holds it.
 */
val TokenStore.canBrowseServerDirectories: Boolean
    get() = holds(Capability.BACKUP_CREATE)
