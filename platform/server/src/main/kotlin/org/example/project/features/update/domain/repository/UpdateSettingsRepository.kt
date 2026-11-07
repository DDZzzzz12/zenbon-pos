package org.example.project.features.update.domain.repository

import org.example.project.features.update.domain.models.UpdateCheckOutcome
import org.example.project.features.update.domain.models.UpdateSettings
import org.example.project.features.update.domain.models.UpdateSettingsEdit

/**
 * Reads and writes the single `update_settings` row.
 *
 * This is the **only** place the update token is encrypted or decrypted -- no caller ever handles
 * ciphertext, and no caller ever supplies the key.
 */
interface UpdateSettingsRepository {
    /** Never null: the row is created with its defaults on first read if it is missing. */
    suspend fun load(): UpdateSettings

    /** Replaces the operator-editable settings; leaves the token and the last check untouched. */
    suspend fun save(edit: UpdateSettingsEdit): UpdateSettings

    /** Seals [token] with this host's key and stores it. Fails when no key is provisioned. */
    suspend fun setToken(token: String)

    /** Removes the ciphertext and the nonce together, so no half-written token can survive. */
    suspend fun clearToken()

    /** Records what the nightly check found. */
    suspend fun recordCheck(outcome: UpdateCheckOutcome)

    /** Remembers where a downloaded artifact was staged, or clears it with null. */
    suspend fun setStagedPath(path: String?)
}
