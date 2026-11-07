package org.example.project.core.data.remote.impl

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.core.data.remote.CustomExportApiClient
import org.example.project.core.data.remote.CustomExportRunResult
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.network.FileDownload
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import org.example.project.core.network.error.apiCallRaw

/**
 * Throws-style, like [KtorDatevExportApiClient] which the run call is modelled on.
 */
class KtorCustomExportApiClient(
    private val httpCall: HttpCall,
) : CustomExportApiClient {
    companion object {
        private const val BASE_ENDPOINT = "api/custom-exports"
        private const val ROW_COUNT_HEADER = "X-Export-Row-Count"
        private const val TRUNCATED_HEADER = "X-Export-Truncated"
    }

    private fun url(path: String): String = "${httpCall.getBaseUrl()}$path"

    override suspend fun catalogue(): List<CustomExportDto.SubjectDto> =
        httpCall.httpClient.apiCall {
            get(url("$BASE_ENDPOINT/catalogue"))
        }

    override suspend fun list(): List<CustomExportDto.DefinitionDto> =
        httpCall.httpClient.apiCall {
            get(url(BASE_ENDPOINT))
        }

    override suspend fun save(request: CustomExportDto.SaveDefinitionRequestDto): CustomExportDto.DefinitionDto =
        httpCall.httpClient.apiCall {
            post(url(BASE_ENDPOINT)) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun update(
        id: Long,
        request: CustomExportDto.SaveDefinitionRequestDto,
    ): CustomExportDto.DefinitionDto =
        httpCall.httpClient.apiCall {
            put(url("$BASE_ENDPOINT/$id")) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun delete(id: Long) {
        httpCall.httpClient.apiCall<Unit> { delete(url("$BASE_ENDPOINT/$id")) }
    }

    override suspend fun run(id: Long): CustomExportRunResult {
        val resp =
            httpCall.httpClient.apiCallRaw {
                get(url("$BASE_ENDPOINT/$id/run"))
            }
        // The filename is the server's, taken from Content-Disposition and never re-derived here:
        // the server sanitises it and deliberately keeps it clear of the DATEV `EXTF_` prefix, and a
        // second copy of that rule in the client is how a file comes to be named for a format it is not.
        val fileName =
            resp.headers[HttpHeaders.ContentDisposition]
                ?.let { runCatching { ContentDisposition.parse(it) }.getOrNull() }
                ?.parameter(ContentDisposition.Parameters.FileName)
        val download =
            FileDownload(
                bytes = resp.body(),
                fileName = fileName,
                contentType = resp.contentType()?.let { "${it.contentType}/${it.contentSubtype}" },
            )
        return CustomExportRunResult(
            download = download,
            // Absent or unparseable stays null — "unknown", which a caller renders differently from a
            // real zero. Only the literal "true" counts as truncated, so a missing header reads as
            // "not truncated" rather than as a warning nobody can act on.
            rowCount = resp.headers[ROW_COUNT_HEADER]?.toIntOrNull(),
            truncated = resp.headers[TRUNCATED_HEADER]?.equals("true", ignoreCase = true) == true,
        )
    }
}
