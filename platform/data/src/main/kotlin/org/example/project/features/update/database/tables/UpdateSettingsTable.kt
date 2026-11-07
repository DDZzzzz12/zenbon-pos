package org.example.project.features.update.database.tables

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/**
 * The single update-check settings row.
 *
 * Migrations do NOT run on a fresh install -- Exposed creates the schema and Flyway is baselined
 * past the chain -- so this object is what creates the table there, while
 * `V87__schema_app_update.sql` -- plus `V88__update_last_blocked_reason.sql` -- creates it on an
 * upgrade. The two must stay column-for-column identical; `SchemaFlywayParityTest` is what catches
 * them drifting apart.
 *
 * The CHECK constraints (singleton id, channel domain, token pair) live in the migration only:
 * Exposed does not model them. [SINGLETON_ID] is the row every read and write targets.
 */
object UpdateSettingsTable : Table("update_settings") {
    val id = short("id").default(SINGLETON_ID)
    val channel = text("channel").default("PUBLIC")
    val apiBaseUrl = text("api_base_url").default("https://api.github.com")
    val autoApply = bool("auto_apply").default(false)

    /** Base64 AES-256-GCM ciphertext. Always moves together with [tokenNonce]. */
    val tokenCiphertext = text("token_ciphertext").nullable()
    val tokenNonce = text("token_nonce").nullable()
    val tokenSetAt = timestampWithTimeZone("token_set_at").nullable()

    val lastCheckedAt = timestampWithTimeZone("last_checked_at").nullable()
    val lastCheckOutcome = text("last_check_outcome").nullable()

    /**
     * The `PreflightVerdict.Reason` name that refused the last unattended install, or null.
     *
     * Deliberately free text: a reason this build does not know must still store and read back,
     * because the alternative is a failed write on the one path nobody is watching.
     */
    val lastBlockedReason = text("last_blocked_reason").nullable()
    val availableVersion = text("available_version").nullable()
    val availableNotes = text("available_notes").nullable()
    val availableAssetId = long("available_asset_id").nullable()
    val stagedPath = text("staged_path").nullable()

    /**
     * `now()`, spelled exactly as `V87__schema_app_update.sql` spells it.
     *
     * Exposed's `CurrentTimestampWithTimeZone` renders `CURRENT_TIMESTAMP`, and PostgreSQL stores
     * that verbatim in `information_schema.columns.column_default` for a `timestamptz` column
     * rather than folding it to `now()` — so a fresh install ended up with the literal default
     * `CURRENT_TIMESTAMP` while an upgraded install had `now()`. The two are the same instant, but
     * they are not the same schema, which is precisely the divergence `SchemaFlywayParityTest`
     * exists to prevent. The migration is the shipped artifact, so the Exposed object follows it.
     */
    val updatedAt =
        timestampWithTimeZone("updated_at").let { column ->
            column.defaultExpression(CustomFunction("now", column.columnType))
        }

    override val primaryKey = PrimaryKey(id)

    const val SINGLETON_ID: Short = 1
}
