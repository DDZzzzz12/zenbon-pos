package org.example.project.features.debtor.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.features.debtor.domain.mapper.toDto
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.debtor.domain.repository.DebtorRepository
import org.example.project.features.debtor.domain.service.DebtorService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery

/** See [DebtorService]. Every method maps inside its own [dbQuery]. */
class DebtorServiceImpl(
    private val repository: DebtorRepository,
) : DebtorService {
    override suspend fun list(
        activeOnly: Boolean,
        search: String?,
        limit: Int,
        offset: Long,
    ): DebtorDto.ListResponseDto =
        dbQuery {
            DebtorDto.ListResponseDto(
                items =
                    repository.list(activeOnly, search, limit, offset).map {
                        it.toDto(salesInvoiceCount = repository.salesInvoiceCount(it.id.value))
                    },
                total = repository.count(activeOnly, search),
            )
        }

    override suspend fun get(id: Long): DebtorDto.ResponseDto =
        dbQuery {
            val debtor = requireDebtor(id)
            debtor.toDto(salesInvoiceCount = repository.salesInvoiceCount(id))
        }

    override suspend fun create(request: DebtorDto.CreateRequestDto): DebtorDto.ResponseDto {
        rejectInvalid(request.validate())
        return dbQuery {
            requireNumberFree(request.debtorNo, selfId = null)
            requireVatIdFree(request.vatId, selfId = null)
            repository
                .create(
                    shop = requireShop(),
                    name = request.name,
                    legalName = request.legalName,
                    vatId = request.vatId,
                    taxNumber = request.taxNumber,
                    street = request.street,
                    zip = request.zip,
                    city = request.city,
                    country = request.country,
                    email = request.email,
                    phone = request.phone,
                    debtorNo = request.debtorNo,
                    paymentTermsDays = request.paymentTermsDays,
                    defaultRevenueAccountNo = request.defaultRevenueAccountNo,
                    defaultBuCode = request.defaultBuCode,
                    einvoiceFormat = request.einvoiceFormat,
                ).apply { notes = request.notes?.takeIf { it.isNotBlank() } }
                .toDto()
        }
    }

    override suspend fun update(
        id: Long,
        request: DebtorDto.UpdateRequestDto,
    ): DebtorDto.ResponseDto {
        rejectInvalid(request.validate())
        return dbQuery {
            val debtor = requireDebtor(id)
            requireNumberFree(request.debtorNo, selfId = id)
            requireVatIdFree(request.vatId, selfId = id)
            repository
                .update(
                    debtor = debtor,
                    name = request.name,
                    legalName = request.legalName,
                    vatId = request.vatId,
                    taxNumber = request.taxNumber,
                    street = request.street,
                    zip = request.zip,
                    city = request.city,
                    country = request.country,
                    email = request.email,
                    phone = request.phone,
                    debtorNo = request.debtorNo,
                    paymentTermsDays = request.paymentTermsDays,
                    defaultRevenueAccountNo = request.defaultRevenueAccountNo,
                    defaultBuCode = request.defaultBuCode,
                    notes = request.notes,
                    isActive = request.isActive,
                    einvoiceFormat = request.einvoiceFormat,
                ).toDto(salesInvoiceCount = repository.salesInvoiceCount(id))
        }
    }

    override suspend fun delete(id: Long) {
        dbQuery {
            val debtor = requireDebtor(id)
            val invoices = repository.salesInvoiceCount(id)
            if (invoices > 0) {
                throw PosException(
                    PosErrorCode.DEBTOR_IN_USE,
                    HttpStatusCode.Conflict,
                    "Debtor $id is referenced by $invoices invoice(s) and can only be deactivated",
                )
            }
            repository.delete(debtor)
        }
    }

    private fun requireDebtor(id: Long): Debtor =
        repository.findById(id)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Debtor $id not found",
            )

    private fun requireShop(): ShopInfo =
        ShopInfo.all().firstOrNull()
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Shop info not found",
            )

    /**
     * [selfId] is the row being edited, if any. Without it, re-checking a debtor's own unchanged
     * number finds that very row and rejects every other edit the operator makes.
     */
    private fun requireNumberFree(
        debtorNo: String?,
        selfId: Long?,
    ) {
        val candidate = debtorNo?.trim()?.takeIf { it.isNotBlank() } ?: return
        val owner = repository.findByDebtorNo(candidate) ?: return
        if (owner.id.value == selfId) return
        throw PosException(
            PosErrorCode.DEBTOR_NUMBER_TAKEN,
            HttpStatusCode.Conflict,
            "Debtor ${owner.id.value} already carries debtor number $candidate",
        )
    }

    /** Same self-exclusion as [requireNumberFree]; the repository canonicalises spacing and case. */
    private fun requireVatIdFree(
        vatId: String?,
        selfId: Long?,
    ) {
        val candidate = vatId?.takeIf { it.isNotBlank() } ?: return
        val owner = repository.findByVatId(candidate) ?: return
        if (owner.id.value == selfId) return
        throw PosException(
            PosErrorCode.DEBTOR_VAT_ID_TAKEN,
            HttpStatusCode.Conflict,
            "Debtor ${owner.id.value} already carries VAT ID $candidate",
        )
    }

    private fun rejectInvalid(fields: List<String>) {
        if (fields.isEmpty()) return
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "Invalid or missing: ${fields.joinToString()}",
            fields = fields.map { FieldError(field = it, code = fieldErrorCode(it)) },
        )
    }

    /** A blank name is missing; a malformed e-mail ([DebtorDto.FIELD_EMAIL]) is the wrong shape. */
    private fun fieldErrorCode(field: String): String = if (field == DebtorDto.FIELD_EMAIL) "INVALID_FORMAT" else "REQUIRED"
}
