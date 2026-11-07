package org.example.project.admin.features.invoices

import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.document.InvoiceListDto

/** Records calls and replays canned answers; [listCalls] proves the screen re-reads after an action. */
class FakeInvoiceListApiClient : InvoiceListApiClient {
    var rowsByBucket: Map<InvoiceListDto.Bucket, List<InvoiceListDto.RowDto>> = emptyMap()
    var counts: Map<String, Long> = emptyMap()
    var failWith: Throwable? = null

    /** What [getInvoice] replays; left null it stands in for a 404. */
    var invoice: DocumentDto.InvoiceDto? = null

    val listCalls = mutableListOf<InvoiceListDto.Bucket>()
    val getInvoiceCalls = mutableListOf<Long>()
    val reviewed = mutableListOf<Long>()

    override suspend fun list(
        bucket: InvoiceListDto.Bucket,
        limit: Int,
        offset: Long,
    ): InvoiceListDto.ListResponseDto {
        listCalls += bucket
        failWith?.let { throw it }
        val items = rowsByBucket[bucket].orEmpty()
        return InvoiceListDto.ListResponseDto(
            items = items,
            total = items.size.toLong(),
            bucketCounts = counts,
        )
    }

    override suspend fun getInvoice(invoiceId: Long): DocumentDto.InvoiceDto {
        getInvoiceCalls += invoiceId
        failWith?.let { throw it }
        return invoice ?: throw IllegalStateException("No invoice set on the fake for id=$invoiceId")
    }

    override suspend fun markReviewed(invoiceId: Long): InvoiceListDto.RowDto {
        reviewed += invoiceId
        failWith?.let { throw it }
        return InvoiceListDto.RowDto(bucket = InvoiceListDto.Bucket.FERTIG, invoiceId = invoiceId)
    }
}
