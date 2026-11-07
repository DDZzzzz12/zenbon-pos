package org.example.project.core.data.remote.impl

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.core.data.remote.TseFindingsApiClient
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall

/**
 * Ktor-based implementation of [TseFindingsApiClient].
 *
 * Throws-style like every other client here: `apiCall` decodes a non-2xx into a `PosApiException`
 * carrying its `PosErrorCode`, so a 403 from the TSE administration gate reaches the caller typed.
 *
 * `from`/`to` are deliberately not sent: the register is read whole. An auditor asks which numbers
 * are unaccounted for, not which ones were unaccounted for last month, and a date window would hide
 * the pre-upgrade history the first run exists to reconcile.
 */
class KtorTseFindingsApiClient(
    private val httpCall: HttpCall,
) : TseFindingsApiClient {
    companion object {
        private const val FINDINGS_ENDPOINT = "api/tse/findings"
        private const val EXPLANATIONS_ENDPOINT = "api/tse/explanations"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun findings(): TseExplanationDto.FindingsResponseDto =
        httpCall.httpClient.apiCall {
            get(url(FINDINGS_ENDPOINT))
        }

    override suspend fun explain(request: TseExplanationDto.CreateRequestDto): TseExplanationDto.ExplanationDto =
        httpCall.httpClient.apiCall {
            post(url(EXPLANATIONS_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
}
