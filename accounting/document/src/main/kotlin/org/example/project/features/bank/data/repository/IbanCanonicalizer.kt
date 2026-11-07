package org.example.project.features.bank.data.repository

/**
 * Reduces an IBAN to its comparison form: whitespace removed, upper-cased.
 *
 * Deliberately **not** validation. A malformed IBAN still canonicalises, and storing what the bank
 * actually sent beats rejecting a statement because our checksum implementation disagrees with
 * reality. Validation, if ever wanted, belongs at the form boundary.
 *
 * Pure: no I/O, no database.
 */
fun canonicalIban(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()
