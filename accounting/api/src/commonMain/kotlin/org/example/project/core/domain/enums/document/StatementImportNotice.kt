package org.example.project.core.domain.enums.document

import kotlinx.serialization.Serializable

/**
 * Something the bank-statement import noticed that the operator has to be told, carried on the
 * upload response (`DocumentDto.DocumentResponseDto.statementImportNotices`).
 *
 * [ProcessingStatus] alone cannot say these things: `FAILED` covers an unreadable file and a file
 * whose arithmetic does not add up alike, and `COMPLETED` says nothing about whether the new
 * statement repeats one that is already stored. Both used to reach only the server log, while the
 * import card said nothing but "Beleg angehängt".
 */
@Serializable
enum class StatementImportNotice {
    /**
     * Opening balance plus the entries does not give the stated closing balance. The statement was
     * **not** stored — derived data that is plausibly wrong is worse than none — but the file is
     * archived as a document. The remedy is a correct export from the bank.
     */
    BALANCES_DO_NOT_RECONCILE,

    /**
     * The statement was stored, but its period overlaps one already imported for the same account.
     * Repeated entries are marked as duplicates and kept out of the Zuordnung queue.
     */
    OVERLAPS_EARLIER_STATEMENT,

    /**
     * The file was uploaded **as a bank statement** (declared `fileType` `BANK_STATEMENT`) but carries
     * no camt.053 or MT940 at all. Nothing was imported; the file is archived as an ordinary document.
     *
     * Needed because [ProcessingStatus] belongs to whichever parser recognised the file: an e-invoice
     * picked in "Auszug importieren" by mistake can come back `FAILED` from the *e-invoice* parser,
     * which the import card read as "the statement could not be read" (E2E 18.8). This notice says
     * what actually happened — not a statement, rather than a broken one. The declared type decides
     * only this message; what is parsed still keys off the bytes.
     */
    NOT_A_BANK_STATEMENT,
}
