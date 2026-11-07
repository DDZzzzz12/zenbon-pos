package org.example.project.export.datevstammdaten.model

/**
 * One DATEV Personenkonto, ready to be written.
 *
 * Database-free on purpose: `:fiscal-export` has no Exposed dependency, and the party→account
 * resolution — which is where the interesting decisions are — lives in the server service.
 */
data class StammdatenAccount(
    /** The Personenkonto number. Digits only; length is Sachkontenlänge + 1. */
    val konto: String,
    val name: String,
    /** Max 15 characters; DATEV truncates silently, so the caller decides what is dropped. */
    val kurzbezeichnung: String,
    /** Two-letter country of the VAT id, e.g. `DE`. Greece is `EL`, Northern Ireland `XI`. */
    val euLand: String? = null,
    /** The VAT id **without** its country prefix. */
    val euUstId: String? = null,
    val steuernummer: String? = null,
    val strasse: String? = null,
    /** Digits only — DATEV's regex rejects a letter. The resolver decides what to do otherwise. */
    val postleitzahl: String? = null,
    val ort: String? = null,
    /** ISO country code. Mandatory for a non-German address. */
    val land: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val bankName: String? = null,
    /** The other account of the same company, when it has one. */
    val verknuepftesOposKonto: String? = null,
)
