-- Rename pdf_* tables to document_* tables to support multi-format documents (PDF, JPEG, PNG)

-- 1. Add media_type column to pdf_documents before rename
ALTER TABLE pdf_documents
  ADD COLUMN media_type VARCHAR(50) NOT NULL DEFAULT 'application/pdf';

-- 2. Rename tables
ALTER TABLE pdf_documents RENAME TO documents;
ALTER TABLE pdf_invoice_metadata RENAME TO document_invoice_metadata;
ALTER TABLE pdf_bank_statement_metadata RENAME TO document_bank_statement_metadata;
ALTER TABLE pdf_document_links RENAME TO document_links;

-- 3. Drop old Exposed auto-generated indexes on pdf_documents (now documents)
-- Exposed generates index names as: tablename_columnname1[_columnname2...]
DROP INDEX IF EXISTS pdf_documents_file_type;
DROP INDEX IF EXISTS pdf_documents_uploaded_at;
DROP INDEX IF EXISTS pdf_documents_is_active;
DROP INDEX IF EXISTS pdf_documents_uploaded_by;
DROP INDEX IF EXISTS pdf_documents_content_hash;
DROP INDEX IF EXISTS pdf_documents_file_type_uploaded_at;
DROP INDEX IF EXISTS pdf_documents_file_path;

-- 4. Drop old indexes on pdf_invoice_metadata
DROP INDEX IF EXISTS pdf_invoice_metadata_pdf_document_id;

-- 5. Drop old indexes on pdf_bank_statement_metadata
DROP INDEX IF EXISTS pdf_bank_statement_metadata_pdf_document_id;

-- 6. Drop old indexes on pdf_document_links (now document_links)
DROP INDEX IF EXISTS pdf_document_links_document_id;
DROP INDEX IF EXISTS pdf_document_links_bank_statement_document_id;
DROP INDEX IF EXISTS pdf_document_links_linked_by;
DROP INDEX IF EXISTS pdf_document_links_linked_at;
DROP INDEX IF EXISTS uq_document_links_doc_bankstatement;

-- 7. Create new indexes on documents table
CREATE INDEX idx_documents_file_type ON documents(file_type);
CREATE INDEX idx_documents_uploaded_at ON documents(uploaded_at);
CREATE INDEX idx_documents_is_active ON documents(is_active);
CREATE INDEX idx_documents_uploaded_by ON documents(uploaded_by);
CREATE INDEX idx_documents_content_hash ON documents(content_hash);
CREATE INDEX idx_documents_file_type_uploaded_at ON documents(file_type, uploaded_at);
CREATE UNIQUE INDEX idx_documents_file_path ON documents(file_path);
CREATE INDEX idx_documents_media_type ON documents(media_type);

-- 8. Create new indexes on document_invoice_metadata
CREATE UNIQUE INDEX idx_document_invoice_metadata_document_id ON document_invoice_metadata(pdf_document_id);

-- 9. Create new indexes on document_bank_statement_metadata
CREATE UNIQUE INDEX idx_document_bank_statement_metadata_document_id ON document_bank_statement_metadata(pdf_document_id);

-- 10. Create new indexes on document_links
CREATE UNIQUE INDEX uq_document_links_doc_bankstatement_new ON document_links(document_id, bank_statement_document_id);
CREATE INDEX idx_document_links_document_id ON document_links(document_id);
CREATE INDEX idx_document_links_bank_statement_document_id ON document_links(bank_statement_document_id);
CREATE INDEX idx_document_links_linked_by ON document_links(linked_by);
CREATE INDEX idx_document_links_linked_at ON document_links(linked_at);
