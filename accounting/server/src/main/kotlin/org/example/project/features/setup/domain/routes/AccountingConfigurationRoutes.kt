package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.domain.service.RestoredPathReviewService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.FileSystemUtils
import org.example.project.utils.OsUtils
import org.example.project.utils.dbQuery
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("AccountingConfigurationRoutes")

/**
 * Shop configuration routes owned by the accounting area: DATEV XML export, DATEV account mapping and
 * document storage. Same paths and handlers as before the split of `ConfigurationRoutes`.
 */
fun Route.accountingConfigurationRoutes() {
    val configRepository by inject<ConfigurationRepository>()
    val configManager by inject<IShopConfigurationManager>()
    // A DATEV path stored here may replace a restored path still awaiting review (NOTE-a).
    val restoredPathReview by inject<RestoredPathReviewService>()

    authenticate("auth-jwt") {
        route("/api/v1/config") {
            /**
             * Get the DATEV XML export configuration.
             *
             * Description: Returns the till name and export path written into the DATEV XML the accountant imports.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/datev-xml") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    logger.info("Getting DATEV XML config for shopId: $shopId")

                    val kassenName = ShopConfigurationManager.getDatevXmlKassenName()
                    val exportPath = ShopConfigurationManager.getDatevXmlExportPath()
                    val consultantNumber = ShopConfigurationManager.getDatevXmlConsultantNumber() ?: ""
                    val clientNumber = ShopConfigurationManager.getDatevXmlClientNumber() ?: ""
                    val sammeldebitor = ShopConfigurationManager.getDatevArSammeldebitor() ?: ""
                    val apExportMode = ShopConfigurationManager.getDatevApExportMode()
                    val wirtschaftsjahrBeginn = ShopConfigurationManager.getDatevWirtschaftsjahrBeginn().toString()
                    val sachkontenlaenge = ShopConfigurationManager.getDatevSachkontenlaenge()
                    val festschreibung = ShopConfigurationManager.getDatevFestschreibung()

                    val enabled = ShopConfigurationManager.getDatevAutoEnabled()
                    val kassenbuchCadence = ShopConfigurationManager.getDatevKassenbuchCadence()
                    val apLedgerCadence = ShopConfigurationManager.getDatevApLedgerCadence()
                    val buchungsstapelCadence = ShopConfigurationManager.getDatevBuchungsstapelCadence()
                    val runHour = ShopConfigurationManager.getDatevRunHour()

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.DatevXmlConfigResponseDto(
                            shopId = shopId,
                            kassenName = kassenName,
                            exportPath = exportPath,
                            consultantNumber = consultantNumber,
                            clientNumber = clientNumber,
                            sammeldebitor = sammeldebitor,
                            apExportMode = apExportMode,
                            wirtschaftsjahrBeginn = wirtschaftsjahrBeginn,
                            sachkontenlaenge = sachkontenlaenge,
                            festschreibung = festschreibung,
                            enabled = enabled,
                            kassenbuchCadence = kassenbuchCadence,
                            apLedgerCadence = apLedgerCadence,
                            buchungsstapelCadence = buchungsstapelCadence,
                            runHour = runHour,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get DATEV XML config for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve DATEV XML configuration",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * PUT /api/v1/config/{shopId}/datev-xml
             * Update DATEV XML Schnittstelle configuration for a shop.
             *
             * Validates the exportPath if provided, then saves configuration.
             */
            /**
             * Update the DATEV XML export configuration.
             *
             * Description: Requires the system-configuration permission or MANAGER role. The export path, when given, must be absolute and is checked to exist (or be creatable) and be a writable directory before the configuration is stored. When the DATEV export path is awaiting the post-restore review, storing a path of this installation resolves that entry; another foreign path stays under review and is listed as now configured.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the export path is not absolute, names a file rather than a directory, cannot be created, or is not writable.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be stored (unexpected server fault).
             */
            put("/{shopId}/datev-xml") {
                val shopId =
                    call.parameters["shopId"]?.toLongOrNull()
                        ?: throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.BadRequest,
                            "Invalid shop ID",
                        )

                val request = call.receive<ShopConfigurationDto.UpdateDatevXmlConfigRequestDto>()

                logger.info("Updating DATEV XML config for shopId: $shopId")

                // Validate request
                request.validate()

                // Checked BEFORE the save, which is what this route's contract promises. It used to
                // run afterwards as `OsUtils.ensureDirectoryExists` with its Boolean result
                // discarded, so an unusable path was stored anyway and the operator got an opaque
                // 500 instead of a 400 naming the path. A restored production configuration hits
                // this every time: its Linux export path does not exist on the machine that
                // restored the archive.
                if (request.exportPath.isNotBlank()) {
                    FileSystemUtils.ensureAbsoluteWritableDirectory(request.exportPath)
                }

                // Serialized, never interpolated: kassenName/exportPath/consultantNumber/
                // clientNumber/wirtschaftsjahrBeginn are operator free text, and a quote in
                // any of them would otherwise break out of the JSON string literal.
                val configJson =
                    Json.encodeToString(
                        ShopConfigurationDto.DatevXmlConfig.serializer(),
                        ShopConfigurationDto.DatevXmlConfig(
                            enabled = request.enabled,
                            kassenName = request.kassenName,
                            exportPath = request.exportPath,
                            consultantNumber = request.consultantNumber,
                            clientNumber = request.clientNumber,
                            apExportMode = request.apExportMode,
                            wirtschaftsjahrBeginn = request.wirtschaftsjahrBeginn,
                            sachkontenlaenge = request.sachkontenlaenge,
                            festschreibung = request.festschreibung,
                            kassenbuchCadence = request.kassenbuchCadence,
                            apLedgerCadence = request.apLedgerCadence,
                            buchungsstapelCadence = request.buchungsstapelCadence,
                            runHour = request.runHour,
                        ),
                    )

                // Save the configuration
                dbQuery {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.DATEV_XML_CONFIG,
                        value = configJson,
                        description = "DATEV XML Schnittstelle configuration",
                    )
                    // Sammeldebitor lives in its own flat setting (read by the AR export via
                    // getDatevArSammeldebitor), not inside the DATEV_XML_CONFIG blob. Blank = unconfigured.
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.DATEV_AR_SAMMELDEBITOR,
                        value = request.sammeldebitor.trim(),
                        description = "DATEV Debitoren-Sammeldebitor (Personenkonto)",
                    )
                }

                // Force refresh of configuration cache
                configManager.refreshConfiguration()
                restoredPathReview.onPathConfigured(RestoredPathSetting.DATEV_EXPORT_PATH, request.exportPath)

                logger.info("Successfully updated DATEV XML config for shopId: $shopId")

                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.DatevXmlConfigResponseDto(
                        shopId = shopId,
                        kassenName = request.kassenName,
                        exportPath = request.exportPath,
                        consultantNumber = request.consultantNumber,
                        clientNumber = request.clientNumber,
                        sammeldebitor = request.sammeldebitor.trim(),
                        apExportMode = request.apExportMode,
                        wirtschaftsjahrBeginn = request.wirtschaftsjahrBeginn,
                        sachkontenlaenge = request.sachkontenlaenge,
                        festschreibung = request.festschreibung,
                        enabled = request.enabled,
                        kassenbuchCadence = request.kassenbuchCadence,
                        apLedgerCadence = request.apLedgerCadence,
                        buchungsstapelCadence = request.buchungsstapelCadence,
                        runHour = request.runHour,
                    ),
                )
            }

            /**
             * Get the DATEV account mapping.
             *
             * Description: Maps payment types and VAT classes onto the accountant's DATEV ledger accounts. A null mapping means it has never been configured.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/datev-account-mapping") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid shop ID"))
                    return@get
                }
                try {
                    val mapping = configManager.getDatevAccountMapping()
                    call.respond(
                        HttpStatusCode.OK,
                        DatevAccountMappingDto.MappingResponseDto(shopId = shopId, mapping = mapping),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get DATEV account mapping for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve DATEV account mapping", "message" to (e.message ?: "Unknown error")),
                    )
                }
            }

            /**
             * Update the DATEV account mapping.
             *
             * Description: Requires the system-configuration permission or MANAGER role. The body replaces the whole mapping; it is validated first, and a refusal is a VALIDATION_FAILED problem+json naming every failing cell as a field error.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, the body is not a mapping (INVALID_REQUEST), or the mapping failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/datev-account-mapping") {
                val shopId = call.shopIdOrThrow()
                val request =
                    try {
                        call.receive<DatevAccountMappingDto.MappingDto>()
                    } catch (e: ContentTransformationException) {
                        throw malformedDatevAccountMapping(e)
                    } catch (e: SerializationException) {
                        throw malformedDatevAccountMapping(e)
                    }
                val errors = request.validate()
                if (errors.isNotEmpty()) throw invalidDatevAccountMapping(errors)
                dbQuery {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.DATEV_ACCOUNT_MAPPING,
                        value = Json.encodeToString(DatevAccountMappingDto.MappingDto.serializer(), request),
                        description = "DATEV account mapping (Kassenbuch line kinds)",
                    )
                }
                configManager.refreshConfiguration()
                logger.info("Updated DATEV account mapping for shopId: $shopId (kontenrahmen=${request.kontenrahmen})")
                call.respond(
                    HttpStatusCode.OK,
                    DatevAccountMappingDto.MappingResponseDto(shopId = shopId, mapping = request),
                )
            }

            /**
             * Get the document storage configuration.
             *
             * Description: Returns the effective storage path — the configured one, or the operating system default when unset — the upload size limit in MB, and both the built-in and operator-added document type folders.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 500 The configuration could not be read.
             */
            get("/{shopId}/document-storage") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@get
                }

                try {
                    logger.info("Getting document storage config for shopId: $shopId")

                    // Get effective storage path (config or OS default fallback)
                    val effectivePath = configManager.getDocumentStoragePath()
                    val additionalTypes = configManager.getAdditionalDocumentTypes()

                    // Get max upload size from config
                    val configJson =
                        dbQuery {
                            configRepository.getSetting(shopId, ConfigKey.DOCUMENT_STORAGE_CONFIG.key)
                        }
                    val maxUploadSizeMb =
                        configJson
                            ?.jsonObject
                            ?.get("maxUploadSizeMb")
                            ?.toString()
                            ?.toIntOrNull() ?: 20

                    // Default types from DocumentType enum
                    val defaultTypes = DocumentType.entries.map { it.folderName }

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.DocumentStorageConfigResponseDto(
                            shopId = shopId,
                            path = effectivePath,
                            maxUploadSizeMb = maxUploadSizeMb,
                            defaultDocumentTypes = defaultTypes,
                            additionalDocumentTypes = additionalTypes,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get document storage config for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to retrieve document storage configuration",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            /**
             * Update the document storage configuration.
             *
             * Description: Requires the system-configuration permission or MANAGER role. A non-blank path must already exist, be a directory and be writable — it is checked before anything is stored. Folders for newly added document types are created on save.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, the body failed validation, or the path does not exist, is not a directory, or is not writable.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 500 The configuration could not be saved.
             */
            put("/{shopId}/document-storage") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid shop ID"),
                    )
                    return@put
                }

                try {
                    val request = call.receive<ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto>()

                    logger.info("Updating document storage config for shopId: $shopId")

                    // Validate request
                    request.validate()

                    // Validate path exists and is writable if a non-empty path is provided
                    if (request.path.isNotBlank()) {
                        val pathDir = File(request.path)
                        if (!pathDir.exists()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Path does not exist: ${request.path}"),
                            )
                            return@put
                        }
                        if (!pathDir.isDirectory) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Path is not a directory: ${request.path}"),
                            )
                            return@put
                        }
                        if (!pathDir.canWrite()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Path is not writable: ${request.path}"),
                            )
                            return@put
                        }
                    }

                    // Serialized, never interpolated: `path` and each additionalDocumentTypes entry
                    // are operator free text. validate() screens the type names for blanks, length
                    // and path separators but not for quotes.
                    val configJson =
                        Json.encodeToString(
                            ShopConfigurationDto.DocumentStorageConfig.serializer(),
                            ShopConfigurationDto.DocumentStorageConfig(
                                path = request.path,
                                maxUploadSizeMb = request.maxUploadSizeMb,
                                additionalDocumentTypes = request.additionalDocumentTypes,
                            ),
                        )

                    // Save the configuration
                    dbQuery {
                        configRepository.setSetting(
                            shopId = shopId,
                            configKey = ConfigKey.DOCUMENT_STORAGE_CONFIG,
                            value = configJson,
                            description = "Document storage configuration: storage path, upload limits, and additional document types",
                        )
                    }

                    // Force refresh of configuration cache
                    configManager.refreshConfiguration()

                    // Create directories for additional document types
                    val effectivePath = configManager.getDocumentStoragePath()
                    request.additionalDocumentTypes.forEach { typeName ->
                        val typeDir = File(effectivePath, typeName)
                        if (!typeDir.exists()) {
                            OsUtils.ensureDirectoryExists(typeDir.absolutePath)
                            logger.info("Created additional document type directory: ${typeDir.absolutePath}")
                        }
                    }

                    logger.info("Successfully updated document storage config for shopId: $shopId")

                    // Default types from DocumentType enum
                    val defaultTypes = DocumentType.entries.map { it.folderName }

                    call.respond(
                        HttpStatusCode.OK,
                        ShopConfigurationDto.DocumentStorageConfigResponseDto(
                            shopId = shopId,
                            path = effectivePath,
                            maxUploadSizeMb = request.maxUploadSizeMb,
                            defaultDocumentTypes = defaultTypes,
                            additionalDocumentTypes = request.additionalDocumentTypes,
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    logger.warn("Validation failed for document storage config: ${e.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Validation failed")),
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update document storage config for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to "Failed to update document storage configuration",
                            "message" to (e.message ?: "Unknown error"),
                        ),
                    )
                }
            }

            // ===== BREAK RULES CONFIGURATION =====
        }
    }
}

/**
 * Turns [DatevAccountMappingDto.MappingDto.validate]'s `"<path>: <reason>"` lines into one
 * VALIDATION_FAILED problem carrying **every** failure as a field error, so the operator sees all
 * cells to fix at once instead of one per save attempt.
 */
private fun invalidDatevAccountMapping(errors: List<String>): PosException =
    PosException(
        code = PosErrorCode.VALIDATION_FAILED,
        httpStatus = HttpStatusCode.BadRequest,
        message = "DATEV account mapping is invalid (${errors.size} error(s))",
        fields =
            errors.map { line ->
                val field = line.substringBefore(": ", missingDelimiterValue = "mapping")
                FieldError(field = field, code = line.substringAfter(": "))
            },
    )

/** A request body that is not a [DatevAccountMappingDto.MappingDto] at all (wrong shape or not JSON): a 400, not a 500. */
private fun malformedDatevAccountMapping(cause: Exception): PosException =
    PosException(
        code = PosErrorCode.INVALID_REQUEST,
        httpStatus = HttpStatusCode.BadRequest,
        message = "The body is not a DATEV account mapping: ${cause.message ?: cause::class.simpleName}",
    )
