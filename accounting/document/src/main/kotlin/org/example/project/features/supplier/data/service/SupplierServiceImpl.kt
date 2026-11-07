package org.example.project.features.supplier.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.TaxNumberFormat
import org.example.project.core.utils.VatIdFormat
import org.example.project.features.supplier.domain.mapper.toDto
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.features.supplier.domain.repository.SupplierRepository
import org.example.project.features.supplier.domain.repository.SupplierSort
import org.example.project.features.supplier.domain.service.SupplierService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import java.math.BigDecimal

/** See [SupplierService]. Every method maps inside its own [dbQuery]. */
class SupplierServiceImpl(
    private val repository: SupplierRepository,
    private val configManager: IShopConfigurationManager,
) : SupplierService {
    override suspend fun list(
        provisionalOnly: Boolean,
        search: String?,
        sort: SupplierSort,
        ascending: Boolean,
        limit: Int,
        offset: Long,
    ): SupplierDto.ListResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            // One boundary for both uses: the same instant orders the list and sums the column it is
            // ordered by, so the ordering can never disagree with the figure beside it.
            val yearStart = startOfCurrentYear()
            val suppliers =
                repository.list(provisionalOnly, search, sort, ascending, yearStart, limit, offset)
            val ids = suppliers.map { it.id.value }
            val openTotals = repository.openTotalsFor(ids)
            val ytdSpend = repository.ytdSpendFor(ids, yearStart)
            SupplierDto.ListResponseDto(
                items =
                    suppliers.map { supplier ->
                        val supplierId = supplier.id.value
                        supplier.toDto(
                            openTotal = openTotals[supplierId] ?: BigDecimal.ZERO,
                            ytdSpend = ytdSpend[supplierId] ?: BigDecimal.ZERO,
                            currency = currency,
                        )
                    },
                total = repository.count(provisionalOnly, search),
            )
        }
    }

    override suspend fun get(id: Long): SupplierDto.ResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val supplier = requireSupplier(id)
            val ids = listOf(id)
            supplier.toDto(
                openTotal = repository.openTotalsFor(ids)[id] ?: BigDecimal.ZERO,
                ytdSpend = repository.ytdSpendFor(ids, startOfCurrentYear())[id] ?: BigDecimal.ZERO,
                currency = currency,
            )
        }
    }

    /** First instant of the current calendar year, for the [SupplierDto.ResponseDto.ytdSpend] window. */
    private fun startOfCurrentYear(): LocalDateTime = LocalDateTime(currentTimeStamp().year, 1, 1, 0, 0)

    override suspend fun create(request: SupplierDto.CreateRequestDto): SupplierDto.ResponseDto {
        rejectInvalid(request.validate())
        return dbQuery {
            requireVatIdFree(request.vatId)
            repository
                .create(
                    name = request.name.trim(),
                    vatId = request.vatId,
                    taxNumber = request.taxNumber,
                    street = request.street,
                    zip = request.zip,
                    city = request.city,
                    country = request.country,
                    email = request.email,
                    phone = request.phone,
                    isProvisional = false,
                ).apply {
                    // `repository.create` has no legal-name parameter, so the party it minted never
                    // received one. The DTO reports the party's copy, so without this the legal
                    // name would come back blank on every creditor.
                    partner.legalName = request.legalName
                    creditorNo = request.creditorNo
                    sepaCreditorId = request.sepaCreditorId?.takeIf { it.isNotBlank() }
                    defaultAccountNo = request.defaultAccountNo
                    defaultBuCode = request.defaultBuCode
                    defaultCostCenter = request.defaultCostCenter
                    paymentTermsDays = request.paymentTermsDays
                    notes = request.notes
                }.toDto()
        }
    }

    override suspend fun update(
        id: Long,
        request: SupplierDto.UpdateRequestDto,
    ): SupplierDto.ResponseDto {
        rejectInvalid(request.validate())
        return dbQuery {
            val supplier = requireSupplier(id)
            // Canonicalized *before* the comparison below, not after: an edit that only
            // re-spaces the VAT id must read as unchanged, or the uniqueness pre-check would run,
            // find this very supplier as the owner of the id, and report SUPPLIER_VAT_ID_TAKEN
            // against the row being edited.
            val newVatId = request.vatId?.let { VatIdFormat.canonical(it) }?.takeIf { it.isNotBlank() }
            // Only when it actually moves: re-checking an unchanged VAT ID would find this very row
            // and reject every other edit the operator makes. Compared against the party, which is
            // where the VAT ID lives and what `findByVatId` searches.
            if (newVatId != supplier.partner.vatId) requireVatIdFree(newVatId)
            // Two rows are edited here, and each assignment names the one it lands on — the same
            // shape as `DebtorRepositoryImpl.update`. Under a single `apply` the party fields and
            // the role fields shared one implicit receiver and read alike, which is how "did the
            // edit reach the party?" once became a defect nobody saw in the diff.
            val party = supplier.partner
            // The party carries the identity and the DTO reports it, so the edit lands there and
            // nowhere else.
            party.name = request.name.trim()
            party.normalizedName = normalizeSupplierName(request.name)
            party.legalName = request.legalName
            party.vatId = newVatId
            party.taxNumber = request.taxNumber?.let { TaxNumberFormat.canonical(it) }?.takeIf { it.isNotBlank() }
            party.street = request.street
            party.zip = request.zip
            party.city = request.city
            party.country = request.country
            party.email = request.email
            party.phone = request.phone
            party.updatedAt = currentTimeStamp()

            supplier.creditorNo = request.creditorNo
            // A blank is the same statement as no identifier recorded; storing "" would
            // look like a recorded creditor id and make matching signal 3 fire on nothing.
            supplier.sepaCreditorId = request.sepaCreditorId?.takeIf { it.isNotBlank() }
            supplier.defaultAccountNo = request.defaultAccountNo
            supplier.defaultBuCode = request.defaultBuCode
            supplier.defaultCostCenter = request.defaultCostCenter
            supplier.paymentTermsDays = request.paymentTermsDays
            supplier.notes = request.notes
            supplier.isActive = request.isActive
            // An operator who checked the details has confirmed the supplier.
            supplier.isProvisional = false
            // `create` deliberately leaves this null — a row that has never been updated
            // shows null rather than its creation time, which is what `createdAt` is for.
            supplier.updatedAt = currentTimeStamp()
            supplier.toDto()
        }
    }

    private fun requireSupplier(id: Long): Supplier =
        repository.findById(id)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Supplier $id not found",
            )

    /**
     * The only guard left on a duplicate VAT ID, and therefore no longer a convenience.
     *
     * `supplier_vat_id_unique` is gone with `supplier.vat_id`, and it was deliberately *not*
     * replaced by a unique index on `business_partner.vat_id` — two parties may share one until a
     * human merges them. So this check is the whole of `SUPPLIER_VAT_ID_TAKEN`, and its meaning is
     * now "a creditor already exists for the company with this VAT ID", which is exactly what the
     * create dialog renders next to the VAT field.
     */
    private fun requireVatIdFree(vatId: String?) {
        val key = vatId?.takeIf { it.isNotBlank() } ?: return
        val owner = repository.findByVatId(key) ?: return
        throw PosException(
            PosErrorCode.SUPPLIER_VAT_ID_TAKEN,
            HttpStatusCode.Conflict,
            "Supplier ${owner.id.value} already carries VAT ID $key",
        )
    }

    private fun rejectInvalid(fields: List<String>) {
        if (fields.isEmpty()) return
        throw PosException(
            PosErrorCode.INVALID_REQUEST,
            HttpStatusCode.BadRequest,
            "Invalid supplier: ${fields.joinToString()}",
            fields = fields.map { FieldError(field = it, code = fieldErrorCode(it)) },
        )
    }

    /** A blank name is missing; a malformed e-mail is the wrong shape. */
    private fun fieldErrorCode(field: String): String = if (field == SupplierDto.FIELD_EMAIL) "INVALID_FORMAT" else "REQUIRED"
}
