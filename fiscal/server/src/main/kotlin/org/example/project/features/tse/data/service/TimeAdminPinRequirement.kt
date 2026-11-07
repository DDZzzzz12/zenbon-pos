package org.example.project.features.tse.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/** The field name a missing TimeAdmin PIN is reported under — the client keys its hint on it. */
internal const val TIME_ADMIN_PIN_FIELD = "timeAdminPin"

/**
 * Refuses a TSE configuration (or a setup) that has no TimeAdmin PIN.
 *
 * Setup writes the TimeAdmin PIN to the device together with the Admin PIN, so without one there is
 * nothing to commission with. This used to surface as `TseConfig.validate()`'s bare
 * `IllegalArgumentException` text under `TSE_INVALID_PARAMETERS`, which the client has no
 * translation for — the operator read "Something went wrong" (found on a real Swissbit TSE).
 * `TSE_TIME_ADMIN_PIN_REQUIRED` with a `timeAdminPin` field error lets the client name the field
 * instead — the code was the generic `VALIDATION_FAILED` until QA 2026-10-06 found a SIMULATION save
 * still answered with the "unexpected error" dialog.
 */
internal fun requireTimeAdminPinConfigured(timeAdminPin: String) {
    if (timeAdminPin.isNotBlank()) return
    throw PosException(
        code = PosErrorCode.TSE_TIME_ADMIN_PIN_REQUIRED,
        httpStatus = HttpStatusCode.BadRequest,
        message = "A TimeAdmin PIN is required. Enter it in the TSE configuration before setting up the TSE.",
        fields = listOf(FieldError(field = TIME_ADMIN_PIN_FIELD, code = "REQUIRED")),
    )
}
