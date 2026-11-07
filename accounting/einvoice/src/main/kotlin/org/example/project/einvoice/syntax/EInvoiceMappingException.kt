package org.example.project.einvoice.syntax

/**
 * A term the [org.example.project.einvoice.model.EInvoice] model requires was not present in the
 * document — for example an invoice with no `ram:ID`, no issue date, or no seller name.
 *
 * This is deliberately a refusal rather than a default. The model's non-null fields are the ones a
 * booking, a DATEV export and a VAT return all read; substituting a placeholder (an empty invoice
 * number, or the `1970-01-01` epoch as an issue date) would push a value that looks like data but
 * is not into the ledger, where nothing downstream can tell it apart from something the supplier
 * really sent. A missing mandatory term must stop the mapping so a later stage can report it as a
 * parse failure.
 *
 * It mirrors [org.example.project.securexml.XmlValueException]'s contract for corrupt values:
 * optional terms stay `null`, mandatory ones throw.
 */
class EInvoiceMappingException(
    expression: String,
    message: String = "Mandatory e-invoice term is missing",
) : Exception("$message: \"$expression\"")
