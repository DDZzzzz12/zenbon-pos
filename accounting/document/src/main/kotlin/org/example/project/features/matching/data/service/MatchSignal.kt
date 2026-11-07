package org.example.project.features.matching.data.service

/** Which rule fired, numbered as in the spec's ladder so a suggestion can be traced back to it. */
enum class MatchSignal {
    /** 1 — `remittance_structured_ref` or `end_to_end_id` equals an invoice number or payment reference. */
    STRUCTURED_REFERENCE,

    /** 2 — an invoice number found inside `remittance_unstructured`, after normalisation. */
    REMITTANCE_TEXT,

    /**
     * 3 — the direct debit's SEPA `mandate_id` is a contract's recorded mandate, or its `creditor_id`
     * is a supplier's recorded Creditor Identifier and the instalment is that supplier's.
     */
    MANDATE_OR_CREDITOR,

    /** 4 — the counterparty's supplier has exactly one open invoice, and its amount is the payment's. */
    IBAN_SINGLE_OPEN,

    /**
     * 5 — the counterparty IBAN resolves to the instalment's supplier, the amount is inside the
     * contract's tolerance band, and the payment fell near the instalment's due date.
     */
    CONTRACT_WINDOW,

    /**
     * 6 — the counterparty name normalises to the supplier's stored key, the amount is the invoice's
     * open amount exactly, and the payment landed near its due date. No identifier is involved.
     */
    SUPPLIER_NAME_AMOUNT_DATE,

    /**
     * 6′ (AR) — the counterparty name normalises to the **debtor's** name, the amount is the
     * sales invoice's open amount exactly, and the payment landed near its due date. The AR analogue
     * of [SUPPLIER_NAME_AMOUNT_DATE], for the CREDIT ladder.
     */
    DEBTOR_NAME_AMOUNT_DATE,

    /** 7 — a subset of that supplier's open invoices sums to the payment. */
    SUBSET_SUM,
}
