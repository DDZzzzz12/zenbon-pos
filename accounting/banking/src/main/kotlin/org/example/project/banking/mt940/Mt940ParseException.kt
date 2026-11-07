package org.example.project.banking.mt940

/**
 * A field was present but could not be read as the type MT940 promises.
 *
 * Thrown rather than nulled: a garbled amount that silently became zero would look exactly like a
 * legitimate zero-value entry, and the balance check would then reject the whole statement without
 * anyone knowing which line caused it.
 */
class Mt940ParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
