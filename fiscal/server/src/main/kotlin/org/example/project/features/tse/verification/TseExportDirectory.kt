package org.example.project.features.tse.verification

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.requireRestoredPathReviewed
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.io.File

/**
 * Resolves the directory TSE TAR exports live in — and the one [TseVerificationService] reads
 * from — consolidating what used to be a scattered relative `"exports"` default (design spec
 * §6.1). Both the TSE export operations and the verify service resolve through this class so an
 * export produced with the default output path is always where verification looks for it.
 *
 * Resolves to `<getDatevXmlExportPath()>/tse`, falling back to `exports/tse` when the shop's
 * configured export path is blank (unconfigured). A suspend call because the underlying shop
 * configuration can change at runtime; always `mkdirs()`s the result so callers can write into it
 * immediately. Refuses (409 `RESTORED_PATHS_NEED_REVIEW`) while the DATEV export path, restored
 * from another installation, awaits an administrator's review.
 */
class TseExportDirectory(
    private val config: IShopConfigurationManager,
) {
    suspend fun resolve(): File {
        // The folder sits under the DATEV export path; while that path, restored from another
        // installation, awaits review nothing may be created or written beneath it.
        config.requireRestoredPathReviewed(RestoredPathSetting.DATEV_EXPORT_PATH, "TSE export folder")
        val base = config.getDatevXmlExportPath()
        val dir = if (base.isBlank()) File("exports", "tse") else File(base, "tse")
        dir.mkdirs()
        return dir
    }

    /**
     * The existing file [fileName] names inside [resolve]. A name is all a caller may give: anything
     * resolving outside the directory is refused (400), and a name with no file behind it is 404.
     */
    suspend fun resolveFile(fileName: String): File {
        val baseDir = resolve().canonicalFile
        val target = File(baseDir, fileName).canonicalFile
        val withinBase = target.path.startsWith(baseDir.path + File.separator)
        if (!withinBase) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.BadRequest,
                "fileName must resolve inside the exports directory",
            )
        }
        if (!target.isFile) {
            throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "export file not found: $fileName",
            )
        }
        return target
    }
}
