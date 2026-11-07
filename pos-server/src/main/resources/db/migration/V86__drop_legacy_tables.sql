-- Squashed from V87 (drop_llm_prompts) and V116 (drop_erp_integration), release v1.1.0.
--
-- Numbering note: this file now carries its final number, V86 -- the top of the window. It holds
-- only drops and one delete and reads nothing any other group creates, so it has no ordering
-- dependency; it sits last because that is where the two sources sat.
--
-- Both source migrations only DROP or DELETE, and every target is created OUTSIDE the squash
-- window (V81..V117), so none of these statements can be folded away into a CREATE TABLE the way
-- the schema groups fold their own ALTERs. They must survive the squash verbatim. Verified by
-- grepping the whole migration set for `CREATE TABLE <name>`:
--   llm_prompts               -- V46 (altered by V60). Below the window, so it exists on every
--                                install that has ever run; the drop is REQUIRED, not defensive.
--   kassenbuch_transit_outbox -- V79. Also below the window; the drop is likewise required.
--   shop_configuration        -- no CREATE TABLE in Flyway at all: it belongs to the Exposed base
--                                schema (SchemaManager). It is NOT dropped here, so the DELETE
--                                below is LIVE and had to be carried across.
--
-- Note on the name `erp_integration`: it is a `shop_configuration.key`, never a table. The old
-- V116 dropped `kassenbuch_transit_outbox` and deleted that configuration row; there has never
-- been an `erp_integration` table to drop.
--
-- ---------------------------------------------------------------------------
-- 1. llm_prompts (from V87)
--
-- The LLM document-extraction feature was removed 2026-08-10: structured e-invoice parsing
-- replaced it, and the LLM path produced plausible wrong values an operator could not distinguish
-- from correct ones. Do not reintroduce an OCR/LLM extraction path. Prompts were configuration,
-- not fiscal records, so nothing dropped here is a business record and no retention obligation
-- applies.
--
-- IRREVERSIBLE: this drops stored prompt text. There is no down migration.
--
-- 2. kassenbuch_transit_outbox + the erp_integration config row (from V116)
--
-- The POS-ERP cash-ledger integration announced Geldtransit movements to a back-office POS-ERP
-- instance. It shipped disabled by default and the POS was always fully functional without it.
-- The whole surface is gone: the configuration screen, the two config routes, the transactional
-- outbox, its sweeper and the outbound client.
--
-- `kassenbuch_transit_outbox` holds only delivery bookkeeping -- which advice was owed, delivered,
-- retried or dead-lettered. It carries no fiscal record. Every row references a
-- `kassenbuch_header` that stays exactly where it is, so the Kassenbuch itself -- the
-- §146a-relevant data -- is untouched. An installation that still had undelivered advice loses the
-- queue, not the cash movement: the Geldtransit entries remain in the Kassenbuch and in DSFinV-K;
-- only the record of "POS-ERP was not yet told" disappears. That is the accepted consequence of
-- removing the integration, and matches what the outbox's own `abandon` escape hatch already did
-- per row.
--
-- The `erp_integration` row in `shop_configuration` carries the enabled flag, base URL and target
-- ledger code. The API key was never stored there (it came from the ERP_API_KEY environment
-- variable), but a row written by an older build could still carry an `apiKey` field inside the
-- JSON blob, so deleting the row also retires that credential from the database and from every
-- backup taken after this migration.
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS llm_prompts;

DROP TABLE IF EXISTS kassenbuch_transit_outbox;

DELETE FROM shop_configuration WHERE key = 'erp_integration';
