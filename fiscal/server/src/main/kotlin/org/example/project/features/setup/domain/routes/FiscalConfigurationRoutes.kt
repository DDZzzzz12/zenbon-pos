package org.example.project.features.setup.domain.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.features.kassenbuch.domain.service.CashBookService
import org.example.project.features.kassenbuch.domain.service.KassenbuchDocuments
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.tse.domain.provider.TseAdminProvider
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.BsiCertificationId
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FiscalConfigurationRoutes")

/** Map TSE formFactor to ELSTER Bauform */
private fun mapFormFactor(formFactor: String?): String? =
    when {
        formFactor == null -> null
        formFactor.contains("uSD", ignoreCase = true) || formFactor.contains("SD", ignoreCase = true) -> "SD-Karte"
        formFactor.contains("USB", ignoreCase = true) -> "USB-Stick"
        formFactor.contains("Cloud", ignoreCase = true) -> "Cloud"
        else -> "keine Angabe"
    }

/**
 * Shop configuration routes owned by the fiscal area: cash-book transfer target, Kassenbuch receipt age
 * and the Kassenmeldung data and PDF. Same paths and handlers as before the split of `ConfigurationRoutes`.
 */
fun Route.fiscalConfigurationRoutes() {
    val configRepository by inject<ConfigurationRepository>()
    val tseAdminProvider by inject<TseAdminProvider>()
    val configManager by inject<IShopConfigurationManager>()
    // Only for validating the cash-book transfer target below: the picker's server half needs to
    // know whether the named book exists, is fiscal, and is still active.
    val bookService by inject<CashBookService>()
    // Renders the Kassenmeldung PDF; the PDF generators are accounting's.
    val documents by inject<KassenbuchDocuments>()

    authenticate("auth-jwt") {
        route("/api/v1/config") {
            /**
             * Get the cash book that receives the daily takings.
             *
             * Description: The book each Z-Bon moves the day's Tageseinnahme into, as an automatic Geldtransit. A null cashBookId means the transfer is switched off, which is the default and a legitimate setting rather than a missing value.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/{shopId}/cash-book-transfer-target") {
                val shopId = call.shopIdOrThrow()
                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.CashBookTransferTargetResponseDto(
                        shopId = shopId,
                        cashBookId = configManager.getCashBookTransferTargetId(),
                    ),
                )
            }

            /**
             * Set the cash book that receives the daily takings.
             *
             * Description: Requires the system-configuration permission or MANAGER role. A null id switches the automatic transfer off. Any other value must name an active, non-fiscal book of this shop — the fiscal Ladenkasse is the source of the transfer, and a deactivated book would strand the takings where daily operation cannot see them.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 404 No cash book with that id.
             * Response: 422 The named book is the fiscal Ladenkasse, or is deactivated.
             */
            put("/{shopId}/cash-book-transfer-target") {
                val shopId = call.shopIdOrThrow()
                val request = call.receive<ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto>()
                val targetId = request.cashBookId

                if (targetId != null) {
                    val target =
                        bookService.list().firstOrNull { it.id == targetId }
                            ?: throw PosException(
                                PosErrorCode.RESOURCE_NOT_FOUND,
                                HttpStatusCode.NotFound,
                                "Cash book with id=$targetId not found",
                            )
                    if (target.isFiscal) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "'${target.name}' is the fiscal Ladenkasse and cannot be its own transfer target",
                        )
                    }
                    if (!target.isActive) {
                        throw PosException(
                            PosErrorCode.VALIDATION_FAILED,
                            HttpStatusCode.UnprocessableEntity,
                            "'${target.name}' is deactivated and cannot receive the daily takings",
                        )
                    }
                }

                // Stored as the plain id, or "" for off — the shape ShopConfigurationManager
                // .getCashBookTransferTargetId() already reads on the Z-Bon closing path.
                dbQuery {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.CASH_BOOK_TRANSFER_TARGET,
                        value = targetId?.toString() ?: "",
                        description = "Cash book receiving the daily takings on Z-Bon (Geldtransit)",
                    )
                }
                configManager.refreshConfiguration()
                logger.info("Cash-book transfer target for shopId $shopId set to ${targetId ?: "off"}")

                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.CashBookTransferTargetResponseDto(shopId = shopId, cashBookId = targetId),
                )
            }

            /**
             * Get the Kassenbuch Beleg-date age limit.
             *
             * Description: How many days back a new cash-book entry's Beleg (receipt) date may lie. A Beleg date inside an already closed period is accepted — the entry books into the open period by its booking time — so this limit is the only backdating bound. Defaults to 31 days when unset.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             */
            get("/{shopId}/kassenbuch-beleg-age") {
                val shopId = call.shopIdOrThrow()
                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.KassenbuchBelegAgeResponseDto(
                        shopId = shopId,
                        maxAgeDays = configManager.getKassenbuchMaxBelegAgeDays(),
                    ),
                )
            }

            /**
             * Set the Kassenbuch Beleg-date age limit.
             *
             * Description: Requires the system-configuration permission or MANAGER role. The limit must lie between 1 and 366 days.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number, or the body is missing maxAgeDays.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller lacks the system-configuration permission and is not a MANAGER.
             * Response: 422 maxAgeDays is outside 1..366.
             */
            put("/{shopId}/kassenbuch-beleg-age") {
                val shopId = call.shopIdOrThrow()
                val maxAgeDays = call.receive<ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto>().maxAgeDays
                if (maxAgeDays !in KassenbuchBelegDateRule.ALLOWED_MAX_AGE_DAYS) {
                    throw PosException(
                        PosErrorCode.VALIDATION_FAILED,
                        HttpStatusCode.UnprocessableEntity,
                        "maxAgeDays must be between ${KassenbuchBelegDateRule.ALLOWED_MAX_AGE_DAYS.first} and " +
                            "${KassenbuchBelegDateRule.ALLOWED_MAX_AGE_DAYS.last}, got $maxAgeDays",
                    )
                }
                dbQuery {
                    configRepository.setSetting(
                        shopId = shopId,
                        configKey = ConfigKey.KASSENBUCH_MAX_BELEG_AGE_DAYS,
                        value = maxAgeDays.toString(),
                        description = "Maximum age in days of a Kassenbuch entry's Beleg date",
                    )
                }
                configManager.refreshConfiguration()
                logger.info("Kassenbuch Beleg age limit for shopId $shopId set to $maxAgeDays days")
                call.respond(
                    HttpStatusCode.OK,
                    ShopConfigurationDto.KassenbuchBelegAgeResponseDto(shopId = shopId, maxAgeDays = maxAgeDays),
                )
            }

            /**
             * Get the Kassenmeldung data.
             *
             * Description: Requires MANAGER role. Aggregates shop details, the eAS configuration and live TSE data into the single record § 146a Abs. 4 AO requires to be reported to ELSTER.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 Shop details or the eAS configuration are missing, so the report cannot be assembled.
             * Response: 500 The Kassenmeldung data could not be assembled.
             */
            get("/{shopId}/kassen-meldung") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid shop ID"))
                    return@get
                }

                try {
                    val response = buildKassenMeldungResponse(shopId, configRepository, tseAdminProvider)
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Not found")))
                } catch (e: Exception) {
                    logger.error("Failed to get Kassenmeldung data for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to retrieve Kassenmeldung data", "message" to (e.message ?: "Unknown error")),
                    )
                }
            }

            /**
             * Download the Kassenmeldung as a PDF.
             *
             * Description: Requires MANAGER role. Renders the same Kassenmeldung record as an A4 PDF for the ELSTER submission, returned as a kassenmeldung.pdf attachment.
             *
             * Tag: config
             * Path: shopId Shop identifier.
             * Response: 400 shopId is not a number.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 Shop details or the eAS configuration are missing, so the report cannot be assembled.
             * Response: 500 The PDF could not be generated.
             */
            get("/{shopId}/kassen-meldung/pdf") {
                val shopId = call.parameters["shopId"]?.toLongOrNull()
                if (shopId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid shop ID"))
                    return@get
                }

                try {
                    val response = buildKassenMeldungResponse(shopId, configRepository, tseAdminProvider)
                    val pdfBytes = documents.kassenmeldungPdf(response)
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"kassenmeldung.pdf\"")
                    call.respondBytes(pdfBytes, ContentType.Application.Pdf)
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Not found")))
                } catch (e: Exception) {
                    logger.error("Failed to generate Kassenmeldung PDF for shopId: $shopId", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to generate Kassenmeldung PDF", "message" to (e.message ?: "Unknown error")),
                    )
                }
            }
        }
    }
}

/**
 * Aggregates all Kassenmeldung data for a given shop from the database.
 *
 * Loads shop info, eAS configuration (KASSEN_MELDUNG_CONFIG), and TSE data
 * (serial number, BSI cert ID, description) into a single response DTO.
 *
 * Must be called within a coroutine context; internally uses [dbQuery].
 *
 * @param shopId The shop identifier
 * @param configRepository Repository for reading configuration settings
 * @return Aggregated [ShopConfigurationDto.KassenMeldungResponseDto]
 * @throws NoSuchElementException if the shop is not found
 */
private suspend fun buildKassenMeldungResponse(
    shopId: Long,
    configRepository: ConfigurationRepository,
    tseAdminProvider: TseAdminProvider,
): ShopConfigurationDto.KassenMeldungResponseDto {
    // Fetch live TSE info (outside dbQuery — TSE call is not a DB operation)
    val tseInfo =
        try {
            tseAdminProvider.getInfo()
        } catch (e: PosException) {
            logger.warn("Failed to get TSE info for Kassenmeldung: code=${e.code} msg=${e.message}", e)
            null
        } catch (e: Exception) {
            logger.warn("TSE not available for Kassenmeldung", e)
            null
        }

    return dbQuery {
        // Load shop info
        val shopInfo =
            ShopInfo.findById(shopId)
                ?: throw NoSuchElementException("Shop not found for id: $shopId")

        // Load KASSEN_MELDUNG_CONFIG
        val kassenConfigJson =
            configRepository.getSettingAsString(
                shopId,
                ConfigKey.KASSEN_MELDUNG_CONFIG.key,
            )
        val kassenConfig =
            kassenConfigJson?.let {
                try {
                    Json.decodeFromString<ShopConfigurationDto.KassenMeldungConfigDto>(it)
                } catch (e: Exception) {
                    logger.warn("Failed to parse KASSEN_MELDUNG_CONFIG for shopId: $shopId, using defaults", e)
                    ShopConfigurationDto.KassenMeldungConfigDto()
                }
            } ?: ShopConfigurationDto.KassenMeldungConfigDto()

        // Read bsiCertIdOverride from TSE_CONFIG (optional field)
        val tseConfigJson = configRepository.getSetting(shopId, ConfigKey.TSE_CONFIG.key)
        val bsiCertIdOverride =
            tseConfigJson?.let {
                try {
                    it.jsonObject["bsiCertIdOverride"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    null
                }
            }

        // Use live TSE data if available, fall back to config-based detection
        val tseDescription =
            tseInfo?.tseDescription ?: tseConfigJson?.let {
                try {
                    it.jsonObject["tseDescription"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    null
                }
            }
        val bsiCertId = bsiCertIdOverride ?: BsiCertificationId.fromTseDescription(tseDescription)

        ShopConfigurationDto.KassenMeldungResponseDto(
            betriebsstaetteName = shopInfo.shopName,
            betriebsstaetteStreet = shopInfo.shopAddress ?: "",
            betriebsstaetteZip = shopInfo.zipCode.toString(),
            betriebsstaetteCity = shopInfo.shopCity ?: "",
            betriebsstaetteCountry = shopInfo.shopCountry ?: "DE",
            kasseSeriennr = kassenConfig.kasseSeriennr,
            kasseBrand = kassenConfig.kasseBrand,
            kasseModell = kassenConfig.kasseModell,
            kasseSwBrand = kassenConfig.kasseSwBrand,
            kasseSwVersion = kassenConfig.kasseSwVersion,
            artDesAufzeichnungssystems = kassenConfig.artDesAufzeichnungssystems,
            inbetriebnahme = kassenConfig.inbetriebnahme,
            tseSerialNumberHex = tseInfo?.tseSerialNumberHex,
            tseDescription = tseDescription,
            tseCertificateExpirationDate = tseInfo?.certificateExpirationDate,
            tseFormFactor = mapFormFactor(tseInfo?.formFactor),
            tseBsiCertId = bsiCertId,
        )
    }
}
