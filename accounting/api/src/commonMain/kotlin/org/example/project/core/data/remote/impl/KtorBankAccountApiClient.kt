/**
 * Ktor-based implementation of the bank-account API client.
 *
 * Throws-style, like every other client here: `apiCall` decodes a non-2xx into a
 * `PosApiException` carrying its `PosErrorCode`, so a 409 `BANK_ACCOUNT_IBAN_TAKEN` reaches the
 * caller typed.
 */
package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.BankAccountApiClient
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

class KtorBankAccountApiClient(
    private val httpCall: HttpCall,
) : BankAccountApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/bank-accounts"

        /** The assign endpoint hangs off the statement, not the account — see BankAccountRoutes. */
        private const val STATEMENT_ENDPOINT = "api/bank-statements"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun list(): BankAccountDto.ListResponseDto =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT))
        }

    override suspend fun get(id: Long): BankAccountDto.ResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id"))
        }

    override suspend fun statements(id: Long): BankAccountDto.StatementListResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id/statements"))
        }

    override suspend fun statementTransactions(
        id: Long,
        statementId: Long,
        limit: Int,
        offset: Long,
    ): BankAccountDto.StatementTransactionListResponseDto =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/$id/statements/$statementId/transactions?limit=$limit&offset=$offset"))
        }

    override suspend fun create(request: BankAccountDto.CreateRequestDto): BankAccountDto.ResponseDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun resolveUnassigned(): BankAccountDto.ResolveResponseDto =
        httpCall.httpClient.apiCall {
            post(url("$BASE_ENDPOINT/resolve"))
        }

    override suspend fun assignStatement(
        statementId: Long,
        request: BankAccountDto.AssignStatementRequestDto,
    ): BankAccountDto.ResponseDto =
        httpCall.httpClient.apiCall {
            put(url("$STATEMENT_ENDPOINT/$statementId/account")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
