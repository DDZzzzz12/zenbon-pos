CREATE TABLE llm_prompts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_type VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    system_prompt TEXT NOT NULL,
    extraction_schema TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO llm_prompts (document_type, name, system_prompt, extraction_schema, is_default) VALUES (
    'INVOICE',
    'Rechnung',
    'You are a document analysis assistant for a German POS system. Extract metadata from the provided document image and return ONLY valid JSON matching the specified schema. If a field is not found, use null. Do not include any text outside the JSON object.',
    '{"vendorName":"string or null - Company name (look for GmbH, AG, KG, e.K., etc.)","invoiceNumber":"string or null - Rechnungsnummer","invoiceDate":"string or null - ISO date yyyy-MM-dd (Rechnungsdatum)","dueDate":"string or null - ISO date yyyy-MM-dd (Fälligkeitsdatum)","netAmount":"number or null - Net amount before tax (Nettobetrag)","grossAmount":"number or null - Gross amount including tax (Bruttobetrag)","taxRate":"number or null - Primary tax rate percentage (e.g. 19, 7)","taxAmount":"number or null - Tax amount (MwSt/USt Betrag)","taxLineItems":"array or null - [{\"description\":\"string\",\"netAmount\":number,\"taxRate\":number,\"grossAmount\":number}]","iban":"string or null - IBAN (spaces stripped)","paymentReference":"string or null - Verwendungszweck","supplierCity":"string or null - German PLZ + City (e.g. 80331 Muenchen)","vatId":"string or null - USt-IdNr (e.g. DE123456789)","deliveryDate":"string or null - ISO date yyyy-MM-dd (Liefer-/Leistungsdatum)","currency":"string or null - ISO 4217 currency code (default EUR)"}',
    true
);

INSERT INTO llm_prompts (document_type, name, system_prompt, extraction_schema, is_default) VALUES (
    'BANK_STATEMENT',
    'Kontoauszug',
    'You are a document analysis assistant for a German POS system. Extract metadata from the provided bank statement image and return ONLY valid JSON matching the specified schema. If a field is not found, use null. Do not include any text outside the JSON object.',
    '{"bankName":"string or null - Name of the bank","bic":"string or null - BIC/SWIFT code (8-11 alphanumeric)","iban":"string or null - IBAN (spaces stripped)","accountNumber":"string or null - Account number (Kontonummer)","statementNumber":"string or null - Auszugsnummer","statementDate":"string or null - ISO date yyyy-MM-dd","periodStart":"string or null - ISO date yyyy-MM-dd","periodEnd":"string or null - ISO date yyyy-MM-dd","openingBalance":"number or null - Opening balance (Anfangssaldo)","closingBalance":"number or null - Closing balance (Endsaldo)","currency":"string or null - ISO 4217 currency code (default EUR)"}',
    true
);
