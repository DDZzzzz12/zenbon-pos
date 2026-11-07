package org.example.project.features.backup.data.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.core.config.RestoredPathPolicy
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.setup.database.tables.ConfigKey
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Flags the restored configuration's folder paths that belong to another installation, inside the
 * restore transaction.
 *
 * A backup carries the original shop's absolute paths — the DATEV export folder, the backup folder,
 * the document storage. Restored onto another machine or install, the scheduler would otherwise run
 * the overdue DATEV catch-up straight into the original shop's folder before anyone could look. This
 * step writes the [ConfigKey.RESTORED_PATHS_REVIEW] row that pauses those writers.
 *
 * **Runs in the restore's own JDBC transaction**, so the restored paths and the pause on them become
 * visible in the same commit — there is no window in which a scheduler tick sees one without the
 * other. It also always replaces the row: a backup taken while a review was pending must not carry
 * that (stale) state onto the new install, and a clean restore must clear an old one.
 */
object RestoredPathReviewStep {
    private val logger = LoggerFactory.getLogger(RestoredPathReviewStep::class.java)

    /**
     * Records the foreign paths of every shop's restored config and returns them (merged across
     * shops; there is one in practice). Empty in the normal case of a shop restoring its own backup.
     */
    fun apply(
        conn: Connection,
        appDataDir: String,
    ): Map<RestoredPathSetting, String> {
        val foreignByShop =
            readPathSettings(conn).mapValues { (_, settings) ->
                RestoredPathPolicy.foreignPaths(RestoredPathPolicy.configuredPaths(settings), appDataDir)
            }
        replaceReviewRows(conn, foreignByShop.filterValues { it.isNotEmpty() })
        val merged = foreignByShop.values.fold(emptyMap<RestoredPathSetting, String>()) { acc, m -> acc + m }
        if (merged.isNotEmpty()) {
            logger.warn(
                "RestoredPathReviewStep: restored paths outside this installation ({}) — file writes " +
                    "to them are paused until reviewed: {}",
                appDataDir,
                merged,
            )
        }
        return merged
    }

    private fun readPathSettings(conn: Connection): Map<Long, Map<String, JsonElement?>> {
        val keys = RestoredPathSetting.entries.map { RestoredPathPolicy.configKeyOf(it).key }.distinct()
        val placeholders = keys.joinToString(", ") { "?" }
        val result = mutableMapOf<Long, MutableMap<String, JsonElement?>>()
        conn
            .prepareStatement(
                "SELECT shop_id, key, value::text AS value FROM shop_configuration WHERE key IN ($placeholders)",
            ).use { ps ->
                keys.forEachIndexed { i, key -> ps.setString(i + 1, key) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val parsed = rs.getString("value")?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                        result.getOrPut(rs.getLong("shop_id")) { mutableMapOf() }[rs.getString("key")] = parsed
                    }
                }
            }
        return result
    }

    private fun replaceReviewRows(
        conn: Connection,
        pendingByShop: Map<Long, Map<RestoredPathSetting, String>>,
    ) {
        val key = ConfigKey.RESTORED_PATHS_REVIEW
        conn.prepareStatement("DELETE FROM shop_configuration WHERE key = ?").use { ps ->
            ps.setString(1, key.key)
            ps.executeUpdate()
        }
        pendingByShop.forEach { (shopId, pending) ->
            conn
                .prepareStatement(
                    "INSERT INTO shop_configuration (shop_id, key, value, description, updated_at) " +
                        "VALUES (?, ?, CAST(? AS jsonb), ?, now())",
                ).use { ps ->
                    ps.setLong(1, shopId)
                    ps.setString(2, key.key)
                    ps.setString(3, Json.encodeToString(JsonObject.serializer(), RestoredPathPolicy.encodeReview(pending)))
                    ps.setString(4, key.description)
                    ps.executeUpdate()
                }
        }
    }
}
