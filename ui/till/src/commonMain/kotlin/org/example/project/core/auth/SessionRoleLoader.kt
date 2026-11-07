package org.example.project.core.auth

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.example.project.core.utils.platformIoDispatcher
import org.example.project.ui.auth.SessionState

/**
 * Publishes the signed-in principal's role, identity and capabilities to [PermissionManager], reading them off the UI
 * thread and publishing them on the caller's.
 *
 * The access token is sealed, and opening it can block on the Keystore or keychain, so [PermissionManager.readRoleSnapshot]
 * runs on [ioDispatcher]. The snapshot is published only if [sessionState] is still authenticated when the read returns. A
 * logout on the UI thread — `LoginViewModel.clearLocalSession` clears the tokens, ends the session, then clears the role —
 * can run while the read is in flight, and the Compose effect that started the read is not cancelled until the next
 * recomposition. Published unconditionally, a read returning after that role clear put the signed-out principal's role
 * back. The check and the publish run together on the caller's thread, the thread the logout runs on, so the logout
 * cannot land between them.
 */
class SessionRoleLoader(
    private val permissionManager: PermissionManager,
    private val sessionState: SessionState,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) {
    private val log = Logger.withTag("SessionRoleLoader")

    /** Reads the role snapshot on [ioDispatcher] and publishes it if the session is still authenticated. Returns whether it published. */
    suspend fun load(): Boolean {
        val snapshot = withContext(ioDispatcher) { permissionManager.readRoleSnapshot() } ?: return false
        if (sessionState.status != SessionStatus.AUTHENTICATED) {
            log.i { "The session ended while its role was being read — not publishing it" }
            return false
        }
        permissionManager.applyRoleSnapshot(snapshot)
        return true
    }
}
