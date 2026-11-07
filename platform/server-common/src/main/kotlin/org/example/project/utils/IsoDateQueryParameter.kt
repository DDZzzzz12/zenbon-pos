package org.example.project.utils

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Reads a required `yyyy-MM-dd` query parameter and returns it unchanged once it is known to parse.
 *
 * The services downstream (DATEV exports, the Kassenbuch period queries) call `LocalDate.parse` on
 * the raw string, so a German-formatted `06.05.2026` used to escape them as a
 * `DateTimeParseException` and reach the caller as an opaque 500 (F64). Validating at the route
 * turns it into the 400 it is, naming the parameter and the expected format.
 *
 * A missing parameter stays `INVALID_REQUEST`, as every one of these routes answered before; a
 * present but malformed one is `VALIDATION_FAILED`. `LocalDate.parse` is ISO_LOCAL_DATE with the
 * STRICT resolver, so an impossible day (`2026-02-30`) is refused too.
 */
fun Parameters.requireIsoDate(name: String): String {
    val raw =
        this[name]
            ?: throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "$name required (yyyy-MM-dd)",
            )
    try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "$name '${raw.take(40)}' is not a valid date: expected format yyyy-MM-dd",
        )
    }
    return raw
}
