/**
 * Ktor implementation of [CashBookApiClient].
 *
 * Throws-style (Stage C): `apiCall` decodes an RFC 7807 ProblemDetail into `PosApiException` and a
 * transport failure into `NetworkApiException`, so nothing is caught here.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorCashBookApiClient(
    private val httpCall: HttpCall,
) : CashBookApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/v1/kassenbuch"
        private const val BOOKS_ENDPOINT = "$BASE_ENDPOINT/books"
        private const val TRANSFERS_ENDPOINT = "$BASE_ENDPOINT/transfers"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun listBooks(includeInactive: Boolean): List<CashBookDto.ResponseDto> =
        httpCall.httpClient.apiCall {
            get(url(BOOKS_ENDPOINT)) {
                // Sent only when asked for, so every other caller's request is byte-identical to before.
                if (includeInactive) parameter("includeInactive", true)
            }
        }

    override suspend fun createBook(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BOOKS_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun updateBook(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BOOKS_ENDPOINT/$bookId")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun createTransfer(request: CashBookDto.CreateTransferRequestDto): CashBookDto.TransferResponseDto =
        httpCall.httpClient.apiCall {
            post(url(TRANSFERS_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun reverseTransfer(
        transferId: String,
        reason: String,
    ): CashBookDto.TransferResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$TRANSFERS_ENDPOINT/$transferId/reverse")) {
                contentType(ContentType.Application.Json)
                setBody(CashBookDto.ReverseTransferRequestDto(reason = reason))
            }
        }

    override suspend fun closePreview(bookId: Long): CashBookDto.ClosePreviewDto =
        httpCall.httpClient.apiCall {
            get(url("$BOOKS_ENDPOINT/$bookId/close/preview"))
        }

    override suspend fun close(
        bookId: Long,
        request: CashBookDto.CloseRequestDto,
    ): CashBookDto.CloseResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BOOKS_ENDPOINT/$bookId/close")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun closeHistory(bookId: Long): List<CashBookDto.CloseResponseDto> =
        httpCall.httpClient.apiCall {
            get(url("$BOOKS_ENDPOINT/$bookId/closes"))
        }

    override suspend fun listSigningTerminalCandidates(bookId: Long): List<CashBookDto.SigningTerminalCandidateDto> =
        httpCall.httpClient.apiCall { get(url("$BOOKS_ENDPOINT/$bookId/signing-terminal/candidates")) }

    override suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
    ): CashBookDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$BOOKS_ENDPOINT/$bookId/signing-terminal")) {
                contentType(ContentType.Application.Json)
                setBody(CashBookDto.SigningTerminalRequestDto(terminalId))
            }
        }
}
