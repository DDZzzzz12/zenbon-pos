-- Prevent same document from being linked to multiple kassenbuch entries
-- Partial unique index: only enforce uniqueness on non-null values
CREATE UNIQUE INDEX uq_kassenbuch_linked_document
  ON kassenbuch_header(linked_document_id) WHERE linked_document_id IS NOT NULL;
