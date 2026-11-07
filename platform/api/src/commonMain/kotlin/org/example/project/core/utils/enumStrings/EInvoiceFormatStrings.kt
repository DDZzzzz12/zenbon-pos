package org.example.project.core.utils.enumStrings

/**
 * The display name of an e-invoice format (`ZUGFERD`, `FACTUR_X`, `XRECHNUNG`, `UBL`, `CII`).
 * These are proper names, identical in every language, so there is no language parameter. Matching
 * ignores case and separators; an unknown value is returned unchanged.
 */
fun eInvoiceFormatLabel(raw: String): String =
    when (raw.filter { it.isLetterOrDigit() }.lowercase()) {
        "zugferd" -> "ZUGFeRD"
        "facturx" -> "Factur-X"
        "xrechnung" -> "XRechnung"
        "cii" -> "CII"
        "ubl" -> "UBL"
        else -> raw
    }
