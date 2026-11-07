package org.example.project.banking.camt

/**
 * The document is not a camt.053 statement, or a term the statement cannot do without is missing.
 *
 * Carries what was expected rather than "mapping failed", because whoever holds a rejected bank file
 * needs to know which element was absent before they can ask the bank anything useful.
 */
class CamtMappingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
