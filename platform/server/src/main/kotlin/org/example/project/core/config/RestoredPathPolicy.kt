package org.example.project.core.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.features.setup.database.tables.ConfigKey
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The rules behind the post-restore path review: where each writable path lives in the config
 * store, when a restored path belongs to another installation, what this installation would use
 * instead, and how the pending review is stored under [ConfigKey.RESTORED_PATHS_REVIEW].
 *
 * A path is **foreign** when it is absolute and does not lie inside this installation's app-data
 * directory (`OsUtils.getAppDataDirectory()`, the base every default path derives from). Blank and
 * relative paths are never foreign: blank means "use the default", and a relative path resolves
 * locally. An absolute-looking path from another OS (`C:\…` read on Linux) is foreign too.
 */
object RestoredPathPolicy {
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/].*")

    /** The config entry holding [setting]. */
    fun configKeyOf(setting: RestoredPathSetting): ConfigKey =
        when (setting) {
            RestoredPathSetting.DATEV_EXPORT_PATH -> ConfigKey.DATEV_XML_CONFIG
            RestoredPathSetting.BACKUP_PATH, RestoredPathSetting.BACKUP_COPY_PATH -> ConfigKey.BACKUP_CONFIG
            RestoredPathSetting.DOCUMENT_STORAGE_PATH -> ConfigKey.DOCUMENT_STORAGE_CONFIG
        }

    /** The field inside that entry's JSON object. */
    fun fieldOf(setting: RestoredPathSetting): String =
        when (setting) {
            RestoredPathSetting.DATEV_EXPORT_PATH -> "exportPath"
            RestoredPathSetting.BACKUP_PATH -> "defaultBackupPath"
            RestoredPathSetting.BACKUP_COPY_PATH -> "copyToPath"
            RestoredPathSetting.DOCUMENT_STORAGE_PATH -> "path"
        }

    /** Every configured path setting found in [settings] (config key → value), blanks omitted. */
    fun configuredPaths(settings: Map<String, JsonElement?>): Map<RestoredPathSetting, String> =
        RestoredPathSetting.entries
            .mapNotNull { setting ->
                val json = settings[configKeyOf(setting).key] as? JsonObject
                val raw = (json?.get(fieldOf(setting)) as? JsonPrimitive)?.contentOrNull
                raw?.takeIf { it.isNotBlank() && it != "null" }?.let { setting to it }
            }.toMap()

    /** The subset of [configured] that belongs to another installation. */
    fun foreignPaths(
        configured: Map<RestoredPathSetting, String>,
        appDataDir: String,
    ): Map<RestoredPathSetting, String> = configured.filterValues { isForeign(it, appDataDir) }

    /** True when [path] is absolute and outside [appDataDir]. */
    fun isForeign(
        path: String,
        appDataDir: String,
    ): Boolean {
        if (path.isBlank()) return false
        val parsed = parse(path)
        val nativeAbsolute = parsed?.isAbsolute == true
        if (!nativeAbsolute) return looksAbsoluteElsewhere(path)
        return !isWithin(parsed, appDataDir)
    }

    /** True when [path] equals [base] or lies beneath it. */
    fun isWithin(
        path: String,
        base: String,
    ): Boolean {
        val parsed = parse(path) ?: return false
        return isWithin(parsed.toAbsolutePath(), base)
    }

    /** What this installation uses for [setting] — shown to the admin as "Standard verwenden". */
    fun defaultPath(
        setting: RestoredPathSetting,
        appDataDir: String,
    ): String =
        when (setting) {
            RestoredPathSetting.DATEV_EXPORT_PATH -> File(appDataDir, "exports").path
            RestoredPathSetting.BACKUP_PATH -> File(appDataDir, "backups").path
            RestoredPathSetting.DOCUMENT_STORAGE_PATH -> File(appDataDir, "documents").path
            RestoredPathSetting.BACKUP_COPY_PATH -> "" // no default target: "use default" clears it
        }

    /**
     * The value written to the config store on "use default". The document path is stored blank,
     * which the config manager already resolves to the local default; the DATEV and backup paths
     * have no blank fallback (a blank DATEV path refuses every export), so they get the real folder.
     */
    fun storedDefault(
        setting: RestoredPathSetting,
        appDataDir: String,
    ): String =
        when (setting) {
            RestoredPathSetting.DOCUMENT_STORAGE_PATH, RestoredPathSetting.BACKUP_COPY_PATH -> ""
            else -> defaultPath(setting, appDataDir)
        }

    /**
     * The pending review after an administrator stored [configuredPath] for [setting] through that
     * setting's own configuration endpoint.
     *
     * - not pending, or the same path as under review (e.g. only another field was edited): unchanged
     * - a path that is not foreign (blank, relative, or inside [appDataDir]): the entry is resolved,
     *   because the administrator has replaced the restored path with one of this installation
     * - another foreign path: still pending, but the entry now shows the path that is configured,
     *   so the review never lists a path the configuration no longer holds
     */
    fun afterConfigured(
        pending: Map<RestoredPathSetting, String>,
        setting: RestoredPathSetting,
        configuredPath: String,
        appDataDir: String,
    ): Map<RestoredPathSetting, String> {
        val reviewed = pending[setting] ?: return pending
        if (reviewed == configuredPath) return pending
        if (isForeign(configuredPath, appDataDir)) return pending + (setting to configuredPath)
        return pending - setting
    }

    /** The pending review as stored; tolerant of a missing, empty or malformed row (= nothing pending). */
    fun decodeReview(element: JsonElement?): Map<RestoredPathSetting, String> {
        val json = element as? JsonObject ?: return emptyMap()
        return json.entries
            .mapNotNull { (key, value) ->
                val setting = RestoredPathSetting.entries.firstOrNull { it.name == key } ?: return@mapNotNull null
                val path = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                setting to path
            }.toMap()
    }

    /** The stored form of a pending review; `{}` when nothing is pending. */
    fun encodeReview(pending: Map<RestoredPathSetting, String>): JsonObject =
        JsonObject(pending.entries.associate { (setting, path) -> setting.name to JsonPrimitive(path) })

    /** The wire form of a pending review, ordered as [RestoredPathSetting] declares them. */
    fun toDtos(
        pending: Map<RestoredPathSetting, String>,
        appDataDir: String,
    ): List<RestoredPathsDto.PathDto> =
        RestoredPathSetting.entries.mapNotNull { setting ->
            pending[setting]?.let {
                RestoredPathsDto.PathDto(setting, it, defaultPath(setting, appDataDir), keepAllowed = setting.keepAllowed)
            }
        }

    private fun isWithin(
        path: Path,
        base: String,
    ): Boolean {
        val basePath = parse(base)?.let(::canonical) ?: return false
        return canonical(path).startsWith(basePath)
    }

    /**
     * The path with every symlink resolved as far as it exists, so an alias of the app-data
     * directory (macOS `/tmp` → `/private/tmp`, a symlinked home) is not mistaken for another
     * installation. The not-yet-existing tail is appended to the real path of its nearest existing
     * ancestor; when nothing exists (or resolution fails) the plain normalized path is used.
     */
    private fun canonical(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing: Path? = absolute
        val missingTail = ArrayDeque<Path>()
        while (existing != null && !Files.exists(existing)) {
            existing.fileName?.let { missingTail.addFirst(it) }
            existing = existing.parent
        }
        if (existing == null) return absolute
        val real =
            try {
                existing.toRealPath()
            } catch (_: IOException) {
                return absolute
            }
        return missingTail.fold(real) { acc, part -> acc.resolve(part.toString()) }
    }

    private fun looksAbsoluteElsewhere(path: String): Boolean = path.startsWith("/") || path.startsWith("\\") || WINDOWS_ABSOLUTE.matches(path)

    private fun parse(path: String): Path? =
        try {
            Paths.get(path)
        } catch (_: InvalidPathException) {
            null
        }
}
