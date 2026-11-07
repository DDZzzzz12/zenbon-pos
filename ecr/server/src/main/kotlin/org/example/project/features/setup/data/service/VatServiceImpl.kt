/**
 * Implementation of VatService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 4.
 */
package org.example.project.features.setup.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.payment.domain.service.VatRateAdmission
import org.example.project.features.payment.domain.service.VatService
import org.example.project.features.setup.domain.repository.VatRepository
import org.example.project.features.setup.mapper.toVatResponse
import org.example.project.features.setup.mapper.toVatResponseList
import org.example.project.features.setup.mapper.toVatSummaryList
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Concrete implementation of VatService with German tax compliance validation.
 */
class VatServiceImpl(
    private val vatRepository: VatRepository,
    private val admission: VatRateAdmission,
) : VatService {
    private val logger = LoggerFactory.getLogger(VatServiceImpl::class.java)

    override suspend fun getAllVatRates(): List<VatDto.VatResponseDto> =
        dbQuery {
            val vats = vatRepository.findAll()
            logger.debug("getAllVatRates: retrieved {} VAT rates", vats.size)
            vats.toVatResponseList()
        }

    override suspend fun getVatRateById(vatId: String): VatDto.VatResponseDto? =
        dbQuery {
            vatRepository.findById(vatId)?.toVatResponse()
        }

    override suspend fun getVatRateByClass(vatClass: String): VatDto.VatResponseDto? =
        dbQuery {
            vatRepository.findByVatClass(vatClass)?.toVatResponse()
        }

    override suspend fun getVatRatesByRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): List<VatDto.VatResponseDto> =
        dbQuery {
            vatRepository.findByRateRange(minRate, maxRate).toVatResponseList()
        }

    override suspend fun setStandardGermanVatRates(): List<VatDto.VatResponseDto> =
        dbQuery {
            vatRepository.setStandardGermanRates().toVatResponseList()
        }

    override suspend fun createVatRate(
        employeeId: String,
        request: VatDto.CreateVatRequestDto,
    ): VatDto.VatResponseDto =
        ExceptionConverter.catchingThrows(
            context = "createVatRate(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.VAT,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.vatId, dto.vatClass, "Created VAT rate '${dto.vatClass}' at ${dto.vatRate}%")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                admission.requireMappable(request.vatRate)

                if (vatRepository.vatClassExists(request.vatClass)) {
                    throw vatClassAlreadyTaken(request.vatClass)
                }

                val vat =
                    vatRepository.create(
                        request.vatId,
                        request.vatClass,
                        BigDecimal(request.vatRate.toString()),
                    )
                logger.info("createVatRate: created VAT rate with id={}", vat.id)
                vat.toVatResponse()
            }
        }

    override suspend fun updateVatRate(
        employeeId: String,
        vatId: String,
        request: VatDto.UpdateVatRequestDto,
    ): VatDto.VatResponseDto =
        ExceptionConverter.catchingThrows(
            context = "updateVatRate(vatId=$vatId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.VAT,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.vatId, dto.vatClass, "Updated VAT rate '${dto.vatClass}' to ${dto.vatRate}%")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                request.vatRate?.let { rate -> admission.requireMappable(rate) }

                if (!request.hasChanges()) {
                    logger.debug("updateVatRate: no changes provided for vatId={}", vatId)
                    val current = vatRepository.findById(vatId) ?: throw vatCodeUnknown(vatId)
                    return@dbQuery current.toVatResponse()
                }

                request.vatClass?.let { newVatClass ->
                    val existing = vatRepository.findByVatClass(newVatClass)
                    if (existing != null && existing.id.value.toString() != vatId) {
                        throw vatClassAlreadyTaken(newVatClass)
                    }
                }

                val vatRate = request.vatRate?.let { BigDecimal(it.toString()) }

                // Round-4 KABS-3: DSFinV-K Tz 3.2.6 — the rate booked is the one that applied „im
                // Zeitpunkt der Erfassung des Geschäftsvorfalls"; historical rates get their own IDs
                // (>= 11), they do not overwrite the current one. OrderItem.vat is a LIVE FK, so
                // mutating a referenced row retroactively re-keys and re-nets already-closed
                // businesscases while transactions_vat keeps its frozen amounts — the same closing then
                // exports two different VAT decompositions for one key, and a delivered closing changes
                // its figures on re-export. Same predicate as deleteVatRate's canDelete.
                //
                // Only the RATE is frozen. A vatClass rename carries no fiscal meaning and stays
                // allowed, and re-submitting the identical rate is a no-op rather than a rejection, so a
                // client that PUTs the whole entity back is unaffected.
                //
                // Historisation path (already available, no schema change): create a NEW Vat row for the
                // new statutory rate and repoint VatMappingConfig, which resolves by primary key —
                // ProductTable carries no vat FK, so no product needs touching. The old row keeps
                // serving historical OrderItems untouched.
                val existing = vatRepository.findById(vatId) ?: throw vatCodeUnknown(vatId)
                val rateChanges = vatRate != null && vatRate.compareTo(existing.vatRate) != 0
                if (rateChanges && !vatRepository.canDelete(vatId)) {
                    throw rateIsReferenced(vatId)
                }

                val vat = vatRepository.update(vatId, request.vatClass, vatRate) ?: throw vatCodeUnknown(vatId)
                logger.info("updateVatRate: updated VAT rate vatId={}", vatId)
                vat.toVatResponse()
            }
        }

    override suspend fun deleteVatRate(
        employeeId: String,
        vatId: String,
    ): String =
        ExceptionConverter.catchingThrows(
            context = "deleteVatRate(vatId=$vatId, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.VAT,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { vatClassName ->
                        EntryLogData(vatId, vatClassName, "Deleted VAT rate '$vatClassName'")
                    },
                ),
        ) {
            dbQuery {
                // Check existence before the reference check: canDelete() only counts referencing
                // order items, so an unknown id trivially passes it and the repository then throws
                // a bare IllegalArgumentException, which StatusPages maps to 400 instead of 404.
                if (!vatRepository.exists(vatId)) {
                    throw vatCodeUnknown(vatId)
                }
                if (!vatRepository.canDelete(vatId)) {
                    throw deleteHasReferences("VatRate is referenced by order items and cannot be deleted")
                }
                val vatClassName = vatRepository.delete(vatId)
                logger.info("deleteVatRate: deleted VAT rate vatId={}", vatId)
                vatClassName
            }
        }

    override suspend fun vatRateExists(vatId: String): Boolean =
        dbQuery {
            vatRepository.exists(vatId)
        }

    override suspend fun vatClassExists(vatClass: String): Boolean =
        dbQuery {
            vatRepository.vatClassExists(vatClass)
        }

    override suspend fun canDeleteVatRate(vatId: String): Boolean =
        dbQuery {
            vatRepository.canDelete(vatId)
        }

    override suspend fun getVatRateCount(): Long =
        dbQuery {
            vatRepository.count()
        }

    override suspend fun countVatRatesByRange(
        minRate: BigDecimal,
        maxRate: BigDecimal,
    ): Long =
        dbQuery {
            vatRepository.countByRateRange(minRate, maxRate)
        }

    override suspend fun getVatRatesForSelection(): List<VatDto.VatSummaryDto> =
        dbQuery {
            vatRepository.findAll().toVatSummaryList()
        }

    // --- PosException helpers ---

    private fun vatCodeUnknown(code: String) =
        PosException(
            PosErrorCode.VAT_CODE_UNKNOWN,
            HttpStatusCode.NotFound,
            "Vat code '$code' not found",
        )

    private fun vatClassAlreadyTaken(cls: String) =
        PosException(
            PosErrorCode.VAT_CLASS_ALREADY_TAKEN,
            HttpStatusCode.Conflict,
            "Vat class '$cls' already exists",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    private fun deleteHasReferences(msg: String) =
        PosException(
            PosErrorCode.DELETE_HAS_REFERENCES,
            HttpStatusCode.Conflict,
            msg,
        )

    /** KABS-3: a rate carrying recorded turnover is frozen; the operator must historise instead. */
    private fun rateIsReferenced(vatId: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "VAT rate '$vatId' is referenced by recorded turnover and its rate cannot be changed " +
                "(DSFinV-K Tz 3.2.6). Create a new VAT rate for the new statutory rate and repoint " +
                "the VAT mapping; the existing rate stays valid for historical Geschäftsvorfälle.",
        )
}
