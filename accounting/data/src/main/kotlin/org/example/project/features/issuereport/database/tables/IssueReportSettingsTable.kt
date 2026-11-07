package org.example.project.features.issuereport.database.tables

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/**
 * The single problem-report settings row. Created by Exposed on a fresh install and by
 * `V95__issue_report_settings.sql` on an upgrade; the two must stay column-for-column identical
 * (`SchemaFlywayParityTest`). The CHECK constraints live in the migration only.
 */
object IssueReportSettingsTable : Table("issue_report_settings") {
    val id = short("id").default(SINGLETON_ID)
    val owner = text("owner").nullable()
    val repo = text("repo").nullable()
    val apiBaseUrl = text("api_base_url").default("https://api.github.com")
    val isPublic = bool("is_public").nullable()

    /** The Administrator's explicit opt-in to file reports with the update feature's token. */
    val useUpdateToken = bool("use_update_token").default(false)
    val tokenCiphertext = text("token_ciphertext").nullable()
    val tokenNonce = text("token_nonce").nullable()
    val tokenSetAt = timestampWithTimeZone("token_set_at").nullable()

    /** `now()`, spelled as the migration spells it — see `UpdateSettingsTable.updatedAt`. */
    val updatedAt =
        timestampWithTimeZone("updated_at").let { column ->
            column.defaultExpression(CustomFunction("now", column.columnType))
        }

    override val primaryKey = PrimaryKey(id)

    const val SINGLETON_ID: Short = 1
}
