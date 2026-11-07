package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto

class FakeInvoiceListApiClient(
    private val response: () -> InvoiceListDto.ListResponseDto,
) : InvoiceListApiClient {
    /** Last `limit` observed on [list] — used to prove the dashboard asks for counts, not rows. */
    var lastLimit: Int? = null

    override suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int,
        offset: Long,
    ): InvoiceListDto.ListResponseDto {
        lastLimit = limit
        return response()
    }

    override suspend fun getInvoice(invoiceId: Long): DocumentDto.InvoiceDto = throw NotImplementedError()

    override suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto = throw NotImplementedError()
}
