package org.example.project.core.presentation.error

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.project.core.domain.dto.clienterror.ClientErrorDto
import org.example.project.core.network.HttpCall
import org.example.project.core.network.error.apiCall
import kotlin.time.Clock

/**
 * Posts shown errors to `POST /api/v1/client-errors`.
 *
 * Fire-and-forget on [scope]: [report] returns immediately so the dialog or toast is never
 * delayed by a network round trip, and a failure to report is swallowed — a client that cannot
 * reach the server is exactly the case being reported, and a second error dialog about the
 * reporting itself would be worse than useless.
 *
 * Credentials come from the shared HTTP client, which already attaches `X-Device-Token` and the
 * bearer token to every request; the endpoint accepts either.
 *
 * @param app `POS_TILL` or `ADMIN`.
 * @param appVersion client build identifier, shown in the log line.
 * @param employeeNumberProvider current employee, or null before anyone logs in.
 * @param terminalIdProvider terminal as the client knows it; the server logs its own verified id
 *   separately, so a client that lies here cannot forge the trusted one.
 * @param mayReport false when the report could not be authenticated and is dropped instead of sent.
 *   adminApp has no device token, only the bearer, so while signed out every report (each failed
 *   sign-in, for one) came back 401 (NEW-23 / FO-36). Checked before the de-duplication, so an error
 *   dropped while signed out is still reported if it recurs after sign-in. The till keeps the default:
 *   its device token authenticates it signed out too.
 */
class DefaultClientErrorReporter(
    private val httpCall: HttpCall,
    private val scope: CoroutineScope,
    private val app: String,
    private val appVersion: String,
    private val employeeNumberProvider: () -> String?,
    private val terminalIdProvider: () -> String?,
    private val mayReport: () -> Boolean = { true },
) : ClientErrorReporter {
    /**
     * Ids already sent, so the same error surfacing repeatedly (a retry loop, a recomposition)
     * writes one line rather than flooding the log. Bounded — see [MAX_REMEMBERED].
     */
    private val alreadyReported = mutableSetOf<String>()

    override fun report(
        error: UiError,
        shownTitle: String,
        shownMessage: String,
        screen: String?,
    ) {
        if (!mayReport()) return
        val occurredAt = Clock.System.now()
        val id = error.fingerprint(screen)
        if (!remember(id)) return

        val report =
            ClientErrorDto.ReportRequestDto(
                clientErrorId = id,
                occurredAt = occurredAt.toString(),
                app = app,
                kind = error.kind(),
                title = shownTitle.take(ClientErrorDto.ReportRequestDto.MAX_TEXT),
                message = shownMessage.take(ClientErrorDto.ReportRequestDto.MAX_TEXT),
                code = error.code?.name,
                httpStatus = (error as? UiError.FromServer)?.httpStatus,
                traceId = error.traceId,
                exceptionType = (error as? UiError.Unknown)?.cause?.let { it::class.simpleName },
                exceptionMessage =
                    (error as? UiError.Unknown)
                        ?.cause
                        ?.message
                        ?.take(ClientErrorDto.ReportRequestDto.MAX_TEXT),
                stackTrace =
                    (error as? UiError.Unknown)
                        ?.cause
                        ?.stackTraceToString()
                        ?.take(ClientErrorDto.ReportRequestDto.MAX_STACK),
                screen = screen,
                employeeNumber = employeeNumberProvider(),
                terminalId = terminalIdProvider(),
                appVersion = appVersion,
            )

        scope.launch {
            runCatching {
                httpCall.httpClient.apiCall<ClientErrorDto.ReportResponseDto> {
                    post("${httpCall.getBaseUrl()}api/v1/client-errors") {
                        contentType(ContentType.Application.Json)
                        setBody(report)
                    }
                }
            }
        }
    }

    /** False when this id was already sent. Evicts wholesale once [MAX_REMEMBERED] is reached. */
    private fun remember(id: String): Boolean {
        if (!alreadyReported.add(id)) return false
        if (alreadyReported.size > MAX_REMEMBERED) {
            alreadyReported.clear()
            alreadyReported.add(id)
        }
        return true
    }

    private companion object {
        const val MAX_REMEMBERED = 200
    }
}

/** `FROM_SERVER`, `NETWORK` or `UNKNOWN`, matching the server's accepted values. */
private fun UiError.kind(): String =
    when (this) {
        is UiError.FromServer -> "FROM_SERVER"
        is UiError.Network -> "NETWORK"
        is UiError.Unknown -> "UNKNOWN"
    }

/**
 * Stable id for "the same error".
 *
 * Built from what identifies the failure rather than when it happened, so a retry loop that
 * surfaces the same fault fifty times is one log line. A server error keys on its `traceId`,
 * which is unique per server request — repeated *attempts* therefore still report separately,
 * which is what you want when diagnosing a failing retry.
 */
private fun UiError.fingerprint(screen: String?): String {
    val parts =
        when (this) {
            is UiError.FromServer -> listOf("s", code.name, httpStatus.toString(), traceId ?: "")
            is UiError.Network -> listOf("n", screen ?: "")
            is UiError.Unknown -> listOf("u", cause?.let { it::class.simpleName } ?: "", cause?.message ?: "", screen ?: "")
        }
    return parts.joinToString("|").take(ClientErrorDto.ReportRequestDto.MAX_ID)
}
