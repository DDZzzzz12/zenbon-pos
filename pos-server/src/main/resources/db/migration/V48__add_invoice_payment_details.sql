ALTER TABLE document_invoice_metadata ADD COLUMN payment_details_json TEXT;
ALTER TABLE document_invoice_metadata ADD COLUMN discount_terms_json TEXT;

-- Update extraction schemas with payment details, discount terms, and improved hints
UPDATE llm_prompts SET
    system_prompt = 'You are extracting structured metadata from a German invoice (Rechnung) image. Return ONLY a valid JSON object. Use null for any field you cannot find. Do NOT guess or infer values — only extract what is explicitly written on the document. Amounts must be numbers (not strings). Dates must be in yyyy-MM-dd format. IBAN must have all spaces removed. Extract ALL line items and ALL payment information from the invoice.',
    extraction_schema = '{
  "vendorName": "string or null — The company or person who issued the invoice. Look for names with GmbH, AG, KG, e.K., e.V., OHG, UG, or a person name at the top of the document.",
  "invoiceNumber": "string or null — The invoice reference number (Rechnungsnummer, Rechnungs-Nr., Re-Nr., Beleg-Nr., Lieferschein-Nr.). This is NOT a date. It is typically an alphanumeric code like RE-2025-001, 12345, INV-0042, 0/0/0502/000338.",
  "invoiceDate": "string or null — The date the invoice was issued (Rechnungsdatum, Belegdatum, Datum). Format: yyyy-MM-dd.",
  "dueDate": "string or null — Payment due date (Fälligkeitsdatum, Zahlbar bis, Zahlungsziel). Format: yyyy-MM-dd. null if not stated.",
  "netAmount": "number or null — Total net amount BEFORE tax (Nettobetrag, Netto-Warenwert, Summe netto). This is LESS than the gross amount. Must be a number.",
  "grossAmount": "number or null — Total gross amount INCLUDING tax (Bruttobetrag, Gesamtbetrag, Rechnungsbetrag, Endbetrag). This is MORE than the net amount. Must be a number.",
  "taxRate": "number or null — The primary VAT/tax rate percentage. Common values: 0, 7, 19. Must be a number like 19, not 19%.",
  "taxAmount": "number or null — The total tax amount (MwSt., USt., Umsatzsteuer, Mehrwertsteuer). Must be a number. Should equal grossAmount minus netAmount.",
  "lineItems": "array or null — All individual items/positions on the invoice. Extract each row from the item table: [{\"position\": \"string or null (Pos., Nr.)\", \"description\": \"string (Bezeichnung, Beschreibung, Artikel)\", \"quantity\": number or null (Menge, Anzahl, Stk. — pay attention to units like 24 Fl., 20er Kasten), \"unit\": \"string or null (Einheit: Stk., Std., Pausch., kg, m, Fl., KA, KI)\", \"unitPrice\": number or null (Einzelpreis, Preis/Einheit), \"netAmount\": number or null (Netto, Betrag), \"taxRate\": number or null (MwSt. %), \"grossAmount\": number or null (Brutto)}]. Include every line item even if some fields are missing.",
  "taxLineItems": "array or null — Tax breakdown by rate if multiple tax rates exist: [{\"description\": \"string\", \"netAmount\": number, \"taxRate\": number, \"taxAmount\": number, \"grossAmount\": number}]. null if only one tax rate.",
  "paymentDetails": "array or null — ALL payment methods and amounts used to pay the invoice. Many invoices use multiple payment methods (e.g. deposit credit + cash). Extract each: [{\"method\": \"string (Überweisung, Bar, EC-Karte, Leergutguthaben, Kreditkarte, Lastschrift, Verrechnung, Gutschein)\", \"amount\": number (the amount paid via this method), \"description\": \"string or null (additional info like Leergutguthaben, Rückgeld, Restguthaben)\"}]. For deposit/Leergut invoices, include: the deposit credit used, the invoice amount deducted, and any change/Rückgeld returned.",
  "discountTerms": "object or null — Cash discount / Skonto terms if mentioned: {\"percent\": number (e.g. 2, 3), \"days\": number or null (days to pay for discount), \"deadline\": \"string or null (yyyy-MM-dd)\", \"discountedAmount\": number or null (amount after discount)}. null if no discount terms.",
  "iban": "string or null — The IBAN for payment. Remove ALL spaces (e.g. DE78840550500012032050).",
  "paymentReference": "string or null — Payment reference or purpose (Verwendungszweck, Zahlungsreferenz, Betreff).",
  "supplierCity": "string or null — The vendor PLZ and city (e.g. 40235 Düsseldorf, 80331 München). MUST include the postal code.",
  "vatId": "string or null — VAT identification number (USt-IdNr., USt-Id, UID). Format like DE123456789.",
  "deliveryDate": "string or null — Delivery or service date (Lieferdatum, Leistungsdatum, Leistungszeitraum). Format: yyyy-MM-dd.",
  "currency": "string or null — Currency code. Default EUR if not explicitly stated."
}',
    updated_at = CURRENT_TIMESTAMP
WHERE document_type = 'INVOICE';

UPDATE llm_prompts SET
    system_prompt = 'You are extracting structured metadata from a German bank statement (Kontoauszug) image. Return ONLY a valid JSON object. Use null for any field you cannot find. Do NOT guess or infer values — only extract what is explicitly written on the document. Amounts must be numbers (not strings). Dates must be in yyyy-MM-dd format. IBAN must have all spaces removed. Extract ALL transactions listed on the statement.',
    extraction_schema = '{
  "bankName": "string or null — Name of the bank (e.g. Sparkasse, Deutsche Bank, Volksbank, Commerzbank).",
  "bic": "string or null — BIC/SWIFT code. 8 or 11 alphanumeric characters (e.g. COBADEFFXXX).",
  "iban": "string or null — The account IBAN. Remove ALL spaces.",
  "accountNumber": "string or null — Account number (Kontonummer) if shown separately from IBAN.",
  "statementNumber": "string or null — Statement number (Auszugsnummer, Auszug Nr.).",
  "statementDate": "string or null — Date of the statement. Format: yyyy-MM-dd.",
  "periodStart": "string or null — Start of statement period. Format: yyyy-MM-dd.",
  "periodEnd": "string or null — End of statement period. Format: yyyy-MM-dd.",
  "openingBalance": "number or null — Opening balance (Anfangssaldo, Alter Saldo). Must be a number.",
  "closingBalance": "number or null — Closing balance (Endsaldo, Neuer Saldo). Must be a number.",
  "transactions": "array or null — All transactions listed on the statement. Extract each row: [{\"date\": \"string (Buchungsdatum, Wertstellung). Format: yyyy-MM-dd.\", \"description\": \"string (Verwendungszweck, Buchungstext, Empfänger/Auftraggeber)\", \"counterparty\": \"string or null (Empfänger, Auftraggeber, Name)\", \"counterpartyIban\": \"string or null — IBAN of counterparty. Remove all spaces.\", \"amount\": number (positive for credit/Gutschrift/Haben, negative for debit/Lastschrift/Soll), \"type\": \"string or null (Buchungsart: SEPA-Überweisung, Lastschrift, Dauerauftrag, Kartenzahlung, Gutschrift, Bareinzahlung, Barauszahlung)\"}]. Include every transaction.",
  "currency": "string or null — Currency code. Default EUR if not explicitly stated."
}',
    updated_at = CURRENT_TIMESTAMP
WHERE document_type = 'BANK_STATEMENT';
