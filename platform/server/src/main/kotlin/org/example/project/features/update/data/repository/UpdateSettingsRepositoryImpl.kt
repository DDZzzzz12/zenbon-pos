package org.example.project.features.update.data.repository

import org.example.project.features.update.data.crypto.SealedToken
import org.example.project.features.update.data.crypto.TokenCipher
import org.example.project.features.update.database.tables.UpdateSettingsTable
import org.example.project.features.update.domain.models.UpdateChannel
import org.example.project.features.update.domain.models.UpdateCheckOutcome
import org.example.project.features.update.domain.models.UpdateSettings
import org.example.project.features.update.domain.models.UpdateSettingsEdit
import org.example.project.features.update.domain.repository.UpdateSettingsRepository
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The single place the update token is sealed and opened.
 *
 * The key comes from the host, the ciphertext from the database, so a database restored onto a
 * different machine holds a token nothing here can read. That is expected, not exceptional --
 * `decrypt` returns null instead of throwing -- but it must never be mistaken for "no token was
 * ever set": the operator has to be told to re-enter it. Hence [UpdateSettings.tokenUndecryptable],
 * which is true exactly when ciphertext is present and did not open.
 */
class UpdateSettingsRepositoryImpl(
    private val cipher: TokenCipher,
) : UpdateSettingsRepository {
    override suspend fun load(): UpdateSettings = dbQuery { readRow().toSettings() }

    override suspend fun save(edit: UpdateSettingsEdit): UpdateSettings =
        dbQuery {
            updateRow {
                it[channel] = edit.channel.name
                it[apiBaseUrl] = edit.apiBaseUrl
                it[autoApply] = edit.autoApply
            }
            readRow().toSettings()
        }

    override suspend fun setToken(token: String) {
        require(token.isNotBlank()) { "The update token must not be blank." }
        check(cipher.isAvailable) {
            "No update-token key is provisioned on this host; set UPDATE_TOKEN_KEY before storing a token."
        }
        val sealed = cipher.encrypt(token)
        dbQuery {
            updateRow {
                it[tokenCiphertext] = sealed.ciphertextBase64
                it[tokenNonce] = sealed.nonceBase64
                it[tokenSetAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun clearToken() {
        dbQuery {
            // Both columns and the timestamp go together: update_settings_token_pair_chk rejects a
            // half-cleared token outright, which is the point -- a stray nonce would otherwise read
            // back as an undecryptable token forever.
            updateRow {
                it[tokenCiphertext] = null
                it[tokenNonce] = null
                it[tokenSetAt] = null
            }
        }
    }

    override suspend fun recordCheck(outcome: UpdateCheckOutcome) {
        dbQuery {
            updateRow {
                it[lastCheckedAt] = outcome.checkedAt.atOffset(ZoneOffset.UTC)
                it[lastCheckOutcome] = outcome.outcome
                // Written unconditionally, null included: a blocker from a previous night must not
                // outlive the condition that caused it.
                it[lastBlockedReason] = outcome.blockedReason
                it[availableVersion] = outcome.availableVersion
                it[availableNotes] = outcome.availableNotes
                it[availableAssetId] = outcome.availableAssetId
            }
        }
    }

    override suspend fun setStagedPath(path: String?) {
        dbQuery {
            updateRow { it[stagedPath] = path }
        }
    }

    /** Visible to tests only, to prove the plaintext token never reaches the column. */
    @TestOnlyApi
    suspend fun rawCiphertextForTest(): String? = dbQuery { readRow()[UpdateSettingsTable.tokenCiphertext] }

    private fun readRow(): ResultRow {
        ensureRow()
        return UpdateSettingsTable
            .selectAll()
            .where { UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }
            .single()
    }

    /**
     * The migration seeds the singleton row, but a fresh install never runs migrations -- Exposed
     * builds the schema and leaves the table empty -- so the first read creates it here instead.
     */
    private fun ensureRow() {
        val missing =
            UpdateSettingsTable
                .selectAll()
                .where { UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }
                .empty()
        if (!missing) return
        UpdateSettingsTable.insert { it[id] = SINGLETON_ID }
    }

    private fun updateRow(body: UpdateSettingsTable.(UpdateStatement) -> Unit) {
        ensureRow()
        UpdateSettingsTable.update({ UpdateSettingsTable.id eq UpdateSettingsTable.SINGLETON_ID }) {
            UpdateSettingsTable.body(it)
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun ResultRow.toSettings(): UpdateSettings {
        val ciphertext = this[UpdateSettingsTable.tokenCiphertext]
        val nonce = this[UpdateSettingsTable.tokenNonce]
        val sealed = if (ciphertext != null && nonce != null) SealedToken(ciphertext, nonce) else null
        val token = sealed?.let { cipher.decrypt(it) }
        if (sealed != null && token == null) {
            log.warn(
                "A stored update token exists but this host's key cannot open it; " +
                    "the operator must re-enter it. (Restored backup, or a changed UPDATE_TOKEN_KEY.)",
            )
        }
        return UpdateSettings(
            channel = parseChannel(this[UpdateSettingsTable.channel]),
            apiBaseUrl = this[UpdateSettingsTable.apiBaseUrl],
            autoApply = this[UpdateSettingsTable.autoApply],
            token = token,
            tokenUndecryptable = sealed != null && token == null,
            tokenSetAt = this[UpdateSettingsTable.tokenSetAt]?.toInstant(),
            lastCheckedAt = this[UpdateSettingsTable.lastCheckedAt]?.toInstant(),
            lastCheckOutcome = this[UpdateSettingsTable.lastCheckOutcome],
            lastBlockedReason = this[UpdateSettingsTable.lastBlockedReason],
            availableVersion = this[UpdateSettingsTable.availableVersion],
            availableNotes = this[UpdateSettingsTable.availableNotes],
            availableAssetId = this[UpdateSettingsTable.availableAssetId],
            stagedPath = this[UpdateSettingsTable.stagedPath],
        )
    }

    /**
     * A value outside the enum can only come from a hand-edited database (the CHECK constraint
     * blocks every other route), and the update checker refusing to boot over it would be worse
     * than falling back to the safe public channel.
     */
    private fun parseChannel(stored: String): UpdateChannel =
        UpdateChannel.entries.firstOrNull { it.name == stored }
            ?: UpdateChannel.PUBLIC.also { log.warn("Unknown update channel '$stored' stored; falling back to PUBLIC.") }

    private companion object {
        private val log = LoggerFactory.getLogger(UpdateSettingsRepositoryImpl::class.java)
    }
}
