/**
 * Service interface for synchronizing shop preferences from server.
 *
 * Handles syncing shop information (name, address, mode flags) and shop
 * configuration (voucher settings, currency) to local KeyValueStore.
 * Unlike [SyncService], this has no SQLite dependencies and works in
 * both standalone and bundled-server modes.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.domain.service

/**
 * Preference synchronization service.
 *
 * Syncs shop info and shop configuration from server into local
 * KeyValueStore-backed managers (ShopDataManager, SettingManager).
 * All dependencies use KeyValueStore, so this works regardless of
 * whether SQLite (PosDatabase) is available.
 */
interface PreferenceSyncService {
    /**
     * Synchronizes shop preferences from server to local key-value store.
     *
     * Throws [org.example.project.core.network.error.PosApiException] or
     * [org.example.project.core.network.error.NetworkApiException] on failure;
     * callers should wrap in `runCatchingApi`.
     */
    suspend fun syncPreferences()
}
