package org.example.project.core.network.error

import kotlinx.coroutines.CancellationException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError

/**
 * Wraps an [HttpClient.apiCall] (or any block that throws [PosApiException] /
 * [NetworkApiException]) and returns a typed [kotlin.Result]. Use the
 * [onError] extension below to map failures to a [UiError] for VM state.
 *
 * Rethrows [CancellationException] so suspend cancellation is never swallowed.
 *
 * Usage:
 * ```
 * runCatchingApi { httpClient.apiCall<EmployeeDto>("/api/employees/$id") }
 *     .onSuccess { _employee.value = it }
 *     .onError   { _error.value = it }
 * ```
 */
suspend inline fun <T> runCatchingApi(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: PosApiException) {
        Result.failure(e)
    } catch (e: NetworkApiException) {
        Result.failure(e)
    }

/**
 * Runs [action] when the result represents a failure, mapping the exception to
 * a [UiError]:
 *  - [PosApiException]     -> [UiError.FromServer] (via `toUiError()`)
 *  - [NetworkApiException] -> [UiError.Network] (with optional [retry])
 *  - anything else         -> [UiError.Unknown]
 *
 * Returns the original [kotlin.Result] for chaining with [kotlin.Result.onSuccess].
 */
fun <T> Result<T>.onError(
    retry: (() -> Unit)? = null,
    action: (UiError) -> Unit,
): Result<T> {
    val e = exceptionOrNull() ?: return this
    val uiError: UiError =
        when (e) {
            is PosApiException -> e.toUiError()
            is NetworkApiException -> UiError.Network(retry = retry)
            else -> UiError.Unknown(cause = e)
        }
    action(uiError)
    return this
}
