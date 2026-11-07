package org.example.project.securexml

/**
 * A field was present in the document but its text could not be parsed into the type the caller
 * asked for — e.g. a `BasisAmount` written with a German decimal comma (`1.234,56`) instead of a
 * plain decimal point. This is distinct from the field being absent, which is reported as `null`,
 * not thrown: a garbled value is a corruption, an absent one is an omission.
 *
 * Carries the raw text and the XPath expression that read it, because "which field, and what did
 * they actually send" is the first thing anyone debugging a rejected supplier invoice will want.
 */
class XmlValueException(
    expression: String,
    rawValue: String,
    cause: Throwable? = null,
) : Exception("Cannot parse value of \"$expression\": \"$rawValue\"", cause)
