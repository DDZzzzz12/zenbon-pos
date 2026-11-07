package org.example.project.securexml

/** Raised when input is rejected for safety reasons rather than because it is malformed. */
class XmlSecurityException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
