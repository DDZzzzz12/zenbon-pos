/**
 * Document Type Enumeration
 *
 * Defines the different types of documents managed by the POS system.
 * Each type has its own folder structure for organized file storage and
 * specific properties for display and business logic.
 *
 * File Storage Pattern:
 * - INVOICE: {root}/RECHNUNG/{YYYY-MM}/Rechnung_{vendor}_{invoiceNr}_{date}_{hash4}.pdf
 * - BANK_STATEMENT: {root}/KONTO-AUSZUEGE/{YYYY-MM}/Kontoauszug_{bank}_{stmtNr}_{date}_{hash4}.pdf
 * - CONTRACTS: {root}/VERTRAEGE/{YYYY-MM}/Vertrag_{description}_{date}_{hash4}.pdf
 * - CUSTOM: {root}/custom/{YYYY-MM}/Sonstige_{description}_{date}_{hash4}.pdf
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.enums.document

/**
 * Types of documents for the document management system.
 *
 * Categorizes files according to their business purpose and
 * determines storage location, display names, and feature support.
 *
 * @property displayNameDe German display name for UI
 * @property displayNameEn English display name for UI
 * @property folderName Folder name for file storage organization
 * @property filePrefix Prefix for generated filenames
 * @property supportsLinking Whether this type can be linked to payment records
 */
enum class DocumentType(
    val displayNameDe: String,
    val displayNameEn: String,
    val folderName: String,
    val filePrefix: String,
    val supportsLinking: Boolean,
) {
    /** Formal invoice (Rechnung) documents */
    INVOICE(
        displayNameDe = "RECHNUNG",
        displayNameEn = "Invoice",
        folderName = "RECHNUNG",
        filePrefix = "invoice",
        supportsLinking = true,
    ),

    /**
     * An invoice **we issued** — an Ausgangsrechnung.
     *
     * Deliberately separate from [INVOICE], which means an invoice we *received*. Document search,
     * the Belege archive and the DATEV AP export all read `INVOICE` as creditor-side; filing our own
     * sales there would put them in the wrong box for both the operator and an auditor.
     *
     * `supportsLinking = false`: linking exists to attach a Beleg to a bank transaction on the
     * creditor side. An outgoing invoice is settled through `payment_allocation` instead.
     */
    OUTGOING_INVOICE(
        displayNameDe = "AUSGANGSRECHNUNG",
        displayNameEn = "Outgoing Invoice",
        folderName = "AUSGANGSRECHNUNGEN",
        filePrefix = "ar-invoice",
        supportsLinking = false,
    ),

    /** Bank statement documents for payment matching */
    BANK_STATEMENT(
        displayNameDe = "BANKAUSZÜGE",
        displayNameEn = "Bank Statement",
        folderName = "KONTO-AUSZUEGE",
        filePrefix = "statement",
        supportsLinking = false,
    ),

    /** Contract documents */
    CONTRACTS(
        displayNameDe = "VERTRÄGE",
        displayNameEn = "Contracts",
        folderName = "VERTRAEGE",
        filePrefix = "contract",
        supportsLinking = true,
    ),

    /** Server-generated Eigenbeleg (self-issued receipt) for Kassenbuch entries */
    EIGENBELEG(
        displayNameDe = "EIGENBELEG",
        displayNameEn = "Self-Receipt",
        folderName = "EIGENBELEGE",
        filePrefix = "eigenbeleg",
        supportsLinking = true,
    ),

    /** Server-generated Z-Bon report */
    ZBON_REPORT(
        displayNameDe = "Z-BON",
        displayNameEn = "Z-Bon Report",
        folderName = "Z-BON",
        filePrefix = "zbon",
        supportsLinking = true,
    ),
    ;

    /**
     * Gets the display name based on language code.
     *
     * @param languageCode ISO 639-1 language code ("de", "en")
     * @return Display name in the requested language, defaults to German
     */
    fun getDisplayName(languageCode: String = "de"): String =
        when (languageCode) {
            "en" -> displayNameEn
            else -> displayNameDe
        }

    companion object {
        /**
         * Safely parses a string to DocumentType.
         *
         * @param value String value to parse
         * @return DocumentType or null if invalid
         */
        fun fromStringOrNull(value: String?): DocumentType? {
            if (value == null) return null
            return try {
                valueOf(value.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * Gets all types that support linking to payment records.
         */
        val linkableTypes: List<DocumentType>
            get() = entries.filter { it.supportsLinking }
    }
}
