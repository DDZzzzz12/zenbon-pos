package org.example.project.features.issuereport.data.repository

import org.example.project.features.issuereport.database.tables.IssueReportSettingsTable
import org.example.project.features.issuereport.domain.models.IssueReportSettings
import org.example.project.features.issuereport.domain.repository.IssueReportSettingsRepository
import org.example.project.features.update.data.crypto.SealedToken
import org.example.project.features.update.data.crypto.TokenCipher
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
 * The single place the problem-report token is sealed and opened. Same contract as
 * `UpdateSettingsRepositoryImpl`: a token this host's key cannot open reads back as
 * [IssueReportSettings.tokenUndecryptable], never as "no token".
 */
class IssueReportSettingsRepositoryImpl(
    private val cipher: TokenCipher,
) : IssueReportSettingsRepository {
    override suspend fun load(): IssueReportSettings = dbQuery { readRow().toSettings() }

    override suspend fun saveTarget(
        owner: String,
        repo: String,
        apiBaseUrl: String,
        isPublic: Boolean,
        useUpdateToken: Boolean,
    ): IssueReportSettings =
        dbQuery {
            updateRow {
                it[this.owner] = owner
                it[this.repo] = repo
                it[this.apiBaseUrl] = apiBaseUrl
                it[this.isPublic] = isPublic
                it[this.useUpdateToken] = useUpdateToken
            }
            readRow().toSettings()
        }

    override suspend fun setToken(token: String) {
        require(token.isNotBlank()) { "The problem-report token must not be blank." }
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
            // All three columns go together: issue_report_settings_token_pair_chk rejects a half-cleared token.
            updateRow {
                it[tokenCiphertext] = null
                it[tokenNonce] = null
                it[tokenSetAt] = null
            }
        }
    }

    /** Visible to tests only, to prove the plaintext token never reaches the column. */
    @TestOnlyApi
    suspend fun rawCiphertextForTest(): String? = dbQuery { readRow()[IssueReportSettingsTable.tokenCiphertext] }

    private fun readRow(): ResultRow {
        ensureRow()
        return IssueReportSettingsTable
            .selectAll()
            .where { IssueReportSettingsTable.id eq IssueReportSettingsTable.SINGLETON_ID }
            .single()
    }

    /** A fresh install never runs migrations, so the first read creates the singleton row. */
    private fun ensureRow() {
        val missing =
            IssueReportSettingsTable
                .selectAll()
                .where { IssueReportSettingsTable.id eq IssueReportSettingsTable.SINGLETON_ID }
                .empty()
        if (!missing) return
        IssueReportSettingsTable.insert { it[id] = SINGLETON_ID }
    }

    private fun updateRow(body: IssueReportSettingsTable.(UpdateStatement) -> Unit) {
        ensureRow()
        IssueReportSettingsTable.update({ IssueReportSettingsTable.id eq IssueReportSettingsTable.SINGLETON_ID }) {
            IssueReportSettingsTable.body(it)
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun ResultRow.toSettings(): IssueReportSettings {
        val ciphertext = this[IssueReportSettingsTable.tokenCiphertext]
        val nonce = this[IssueReportSettingsTable.tokenNonce]
        val sealed = if (ciphertext != null && nonce != null) SealedToken(ciphertext, nonce) else null
        val token = sealed?.let { cipher.decrypt(it) }
        if (sealed != null && token == null) {
            log.warn(
                "A stored problem-report token exists but this host's key cannot open it; " +
                    "the operator must re-enter it. (Restored backup, or a changed UPDATE_TOKEN_KEY.)",
            )
        }
        return IssueReportSettings(
            owner = this[IssueReportSettingsTable.owner],
            repo = this[IssueReportSettingsTable.repo],
            apiBaseUrl = this[IssueReportSettingsTable.apiBaseUrl],
            isPublic = this[IssueReportSettingsTable.isPublic],
            token = token,
            tokenUndecryptable = sealed != null && token == null,
            tokenSetAt = this[IssueReportSettingsTable.tokenSetAt]?.toInstant(),
            useUpdateToken = this[IssueReportSettingsTable.useUpdateToken],
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(IssueReportSettingsRepositoryImpl::class.java)
    }
}
