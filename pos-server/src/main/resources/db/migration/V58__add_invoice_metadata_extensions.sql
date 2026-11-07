-- V58__add_invoice_metadata_extensions.sql
-- Add all new columns to document_invoice_metadata for DATEV AP invoice metadata extension

ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS invoice_type VARCHAR(30);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS drawee_no VARCHAR(100);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS order_id VARCHAR(100);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS supplier_street VARCHAR(255);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS supplier_zip VARCHAR(20);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS supplier_country VARCHAR(10);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS supplier_phone VARCHAR(50);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS supplier_email VARCHAR(255);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS ship_to_country VARCHAR(10);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS ship_from_country VARCHAR(10);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS payment_conditions_text VARCHAR(255);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS payment_dunning_block BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS cost_category_id VARCHAR(20);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS cost_category_id2 VARCHAR(20);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS cost_amount DECIMAL(15, 2);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS exchange_rate DECIMAL(18, 6);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS eu_tax_rate DECIMAL(5, 2);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS bu_code VARCHAR(10);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS type_of_receivable VARCHAR(50);
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS delivery_date_start TIMESTAMP;
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS delivery_date_end TIMESTAMP;
ALTER TABLE document_invoice_metadata ADD COLUMN IF NOT EXISTS additional_info_json TEXT;

-- UPSERT updated LLM extraction schema for INVOICE
INSERT INTO llm_prompts (document_type, name, system_prompt, extraction_schema, is_default, is_active, created_at, updated_at)
VALUES (
    'INVOICE',
    'Invoice Extraction v2',
    'You are an expert at extracting structured data from German invoices (Eingangsrechnungen) and credit notes (Gutschriften). For credit notes (Gutschriften), set invoiceType to the appropriate type (e.g., "Gutschrift § 14 UStG") and populate draweeNo with the original invoice number being credited. Always extract the full supplier address including street, postal code, city, and country. Payment conditions should include any discount terms and payment deadlines. For split-tax invoices, extract each tax rate as a separate taxLineItem. The bookingText should be a concise German description max 60 characters.',
    '{"vendorName":"company or person name","invoiceNumber":"Rechnungsnummer","invoiceDate":"YYYY-MM-DD","dueDate":"YYYY-MM-DD","invoiceType":"Rechnung | Gutschrift § 14 UStG | Gutschrift/Rechnungskorrektur | Rechnungskorrektur | Abschlagsrechnung | Schlussrechnung | Bargutschrift | Barrechnung | Barrechnungskorrektur","draweeNo":"original invoice number this credit note refers to","orderId":"order reference number if present","currency":"EUR | USD | etc","iban":"IBAN","paymentReference":"Verwendungszweck / payment reference","supplierCity":"city only","supplierStreet":"street with number","supplierZip":"postal code","supplierCountry":"2-letter country code","supplierPhone":"phone number","supplierEmail":"email address","vatId":"VAT ID (e.g. DE123456789)","shipToCountry":"destination country for OSS","shipFromCountry":"origin country for OSS","deliveryDate":"YYYY-MM-DD","deliveryDateStart":"service period start YYYY-MM-DD","deliveryDateEnd":"service period end YYYY-MM-DD","netAmount":"net total as number","grossAmount":"gross total as number","taxAmount":"tax total as number","taxRate":"tax percentage as number","accountNo":"Gegenkonto / G/L account","bpAccountNo":"Kreditorenkonto / creditor account","bookingText":"short German booking text max 60 chars","paymentConditionsText":"payment terms text","paidAt":"YYYY-MM-DD if marked paid","paymentDunningBlock":"true if payment blocked","costCategoryId":"cost center 1","costCategoryId2":"cost center 2","costAmount":"cost center amount","exchangeRate":"EUR exchange rate (6 decimals)","euTaxRate":"EU cross-border tax rate","buCode":"DATEV B/U key","typeOfReceivable":"receivable type","taxLineItems":[{"netAmount":0.00,"taxRate":0.0,"taxAmount":0.00,"grossAmount":0.00}],"lineItems":[{"position":"line number","description":"item description","quantity":1.0,"unit":"Stück | kg | l | etc","unitPrice":0.00,"netProductPrice":0.00,"grossProductPrice":0.00,"productId":"article/EAN number if shown","deliveryNoteId":"delivery note reference","netAmount":0.00,"taxRate":0.0,"taxAmount":0.00,"grossAmount":0.00,"taxExemptionReason":"tax exemption reason if any","accountingInfo":{"accountNo":"","costCategoryId":"","costCategoryId2":""}}],"paymentDetails":[{"method":"","amount":0.00,"description":""}],"discountTerms":[{"percentage":0.0,"days":0,"amount":0.00,"description":""}],"additionalInfo":[{"type":"","content":""}]}',
    true,
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (document_type) DO UPDATE SET
    system_prompt = EXCLUDED.system_prompt,
    extraction_schema = EXCLUDED.extraction_schema,
    updated_at = CURRENT_TIMESTAMP;
