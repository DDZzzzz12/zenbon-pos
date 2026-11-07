-- A cancelled incoming invoice (Storno) used to leave its ORIGINAL in "Zu prüfen" for ever: only the
-- correction was stamped reviewed, and the client cannot confirm a frozen invoice. New cancellations
-- now stamp the original too; this backfills the ones already cancelled.
--
-- A cancellation is recorded by a correction row (see InvoiceStorno): extraction_source = 'STORNO',
-- same source document, preceding_invoice_reference = the original's invoice_number (null-safe
-- equal), higher id. Idempotent: only rows still unreviewed are touched.
UPDATE invoice
SET reviewed_at = CURRENT_TIMESTAMP
WHERE reviewed_at IS NULL
  AND extraction_source <> 'STORNO'
  AND EXISTS (
      SELECT 1
      FROM invoice c
      WHERE c.extraction_source = 'STORNO'
        AND c.source_document_id = invoice.source_document_id
        AND c.id > invoice.id
        AND (c.preceding_invoice_reference = invoice.invoice_number
             OR (c.preceding_invoice_reference IS NULL AND invoice.invoice_number IS NULL))
  );
