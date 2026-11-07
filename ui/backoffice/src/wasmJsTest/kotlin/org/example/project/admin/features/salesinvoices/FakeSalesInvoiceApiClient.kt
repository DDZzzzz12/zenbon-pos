package org.example.project.admin.features.salesinvoices

import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.domain.dto.datevxml.DatevArExportDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto

/**
 * Records what the ViewModels asked for, so a test can assert the *request* rather than only the
 * resulting state — the bucket filters and the standalone payload are meant to be sent server-side,
 * which is invisible from the state alone.
 */
class FakeSalesInvoiceApiClient : SalesInvoiceApiClient {
    private companion object {
        const val COUNT_PROBE_LIMIT = 1
    }

    var invoices: MutableList<SalesInvoiceDto.ResponseDto> = mutableListOf()
    var listThrows: Throwable? = null
    var getThrows: Throwable? = null
    var saveThrows: Throwable? = null
    var issueThrows: Throwable? = null
    var lastStatus: String? = null
    var lastType: String? = null
    var lastOpen: Boolean? = null
    var lastOverdue: Boolean? = null
    var lastDebtorId: Long? = null
    var createdStandalone: SalesInvoiceDto.CreateStandaloneRequestDto? = null
    var updatedStandalone: Pair<Long, SalesInvoiceDto.UpdateStandaloneRequestDto>? = null
    var deletedIds: MutableList<Long> = mutableListOf()
    var issuedIds: MutableList<Long> = mutableListOf()
    var cancelRequests: MutableList<Pair<Long, SalesInvoiceDto.CancelRequestDto>> = mutableListOf()

    override suspend fun list(
        status: String?,
        type: String?,
        open: Boolean,
        overdue: Boolean,
        debtorId: Long?,
        limit: Int,
        offset: Long,
    ): SalesInvoiceDto.ListResponseDto {
        // A page of one is the view model's count probe for the Belegfluss strip (one per bucket, in
        // parallel): it must not overwrite the request of the list the test is asserting on.
        if (limit != COUNT_PROBE_LIMIT) {
            lastStatus = status
            lastType = type
            lastOpen = open
            lastOverdue = overdue
            lastDebtorId = debtorId
        }
        listThrows?.let { throw it }
        return SalesInvoiceDto.ListResponseDto(
            items = invoices.map { it.toListItem() },
            total = invoices.size.toLong(),
        )
    }

    override suspend fun get(id: Long): SalesInvoiceDto.ResponseDto {
        getThrows?.let { throw it }
        return invoices.firstOrNull { it.id == id } ?: error("no fake sales invoice $id")
    }

    override suspend fun createFromBill(request: SalesInvoiceDto.CreateFromBillRequestDto): SalesInvoiceDto.ResponseDto =
        error("not exercised by the adminApp tests")

    override suspend fun createStandalone(request: SalesInvoiceDto.CreateStandaloneRequestDto): SalesInvoiceDto.ResponseDto {
        createdStandalone = request
        saveThrows?.let { throw it }
        return invoice(id = 100, status = "DRAFT")
    }

    override suspend fun updateStandalone(
        id: Long,
        request: SalesInvoiceDto.UpdateStandaloneRequestDto,
    ): SalesInvoiceDto.ResponseDto {
        updatedStandalone = id to request
        saveThrows?.let { throw it }
        return invoice(id = id, status = "DRAFT")
    }

    override suspend fun deleteStandalone(id: Long) {
        deletedIds += id
    }

    override suspend fun issue(id: Long): SalesInvoiceDto.ResponseDto {
        issuedIds += id
        issueThrows?.let { throw it }
        return invoice(id = id, status = "ISSUED")
    }

    override suspend fun settleAtTill(
        id: Long,
        request: SalesInvoiceDto.SettleAtTillRequestDto,
    ): SalesInvoiceDto.ResponseDto = error("not exercised by the adminApp tests")

    override suspend fun cancel(
        id: Long,
        request: SalesInvoiceDto.CancelRequestDto,
    ): SalesInvoiceDto.ResponseDto {
        cancelRequests += id to request
        return invoices.first { it.id == id }
    }

    override suspend fun pdf(id: Long): ByteArray = byteArrayOf()

    override suspend fun datevExportStatistics(
        start: String,
        end: String,
    ): DatevArExportDto.ArExportStatisticsDto = error("not exercised by the adminApp tests")

    override suspend fun datevExport(
        start: String,
        end: String,
    ): ByteArray = error("not exercised by the adminApp tests")

    private fun SalesInvoiceDto.ResponseDto.toListItem() =
        SalesInvoiceDto.ListItemDto(
            id = id,
            invoiceNumber = invoiceNumber,
            status = status,
            invoiceTypeCode = invoiceTypeCode,
            issueDate = issueDate,
            buyerName = buyerName,
            currency = currency,
            taxInclusiveTotal = taxInclusiveTotal,
            openAmount = openAmount,
            hasDocument = documentId != null,
        )

    private fun invoice(
        id: Long,
        status: String,
    ) = SalesInvoiceDto.ResponseDto(
        id = id,
        invoiceNumber = null,
        status = status,
        revenueOrigin = "STANDALONE",
        invoiceTypeCode = "380",
        precedingInvoiceId = null,
        billId = null,
        debtorId = null,
        buyerName = "Musterfirma GmbH",
        buyerVatId = null,
        buyerStreet = null,
        buyerZip = null,
        buyerCity = null,
        buyerCountry = null,
        buyerEmail = null,
        sellerName = null,
        sellerVatId = null,
        sellerTaxNumber = null,
        issueDate = "2026-08-16T10:00",
        serviceDate = "2026-08-16T10:00",
        dueDate = null,
        currency = "EUR",
        lineNetTotal = null,
        taxTotal = null,
        taxInclusiveTotal = null,
        prepaidAmount = null,
        amountDue = null,
        openAmount = null,
        notes = null,
        paymentTermsText = null,
        revenueAccountNo = null,
        buCode = null,
        documentId = null,
        issuedAt = null,
        cancelledAt = null,
        lines = emptyList(),
        vatBreakdown = emptyList(),
    )
}
