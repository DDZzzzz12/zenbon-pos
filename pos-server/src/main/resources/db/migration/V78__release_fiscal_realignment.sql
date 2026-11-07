-- ===========================================================================
-- V78__release_fiscal_realignment.sql
--
-- CONSOLIDATED migration (2026-08-02). Replaces the 4 per-feature files listed
-- below; content is byte-identical, concatenated in version order. The original
-- per-file header comments are preserved inline so provenance stays traceable.
--
-- Flyway note: this consolidation CHANGES migration checksums. Fresh installs and
-- CI (embedded zonky) are unaffected; any pre-existing DB that already applied the
-- old V{N} files must run `flyway repair` / re-baseline once (see docs).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- [source: V94__drop_frozen_timestamp_defaults.sql]
-- ---------------------------------------------------------------------------
-- Round-4 KABS-1: `datetime(...).default(currentTimeStamp())` baked ONE wall-clock instant into the
-- DDL default (Exposed evaluates the argument once at table-object init), so every row inserted
-- without an explicit value carried the same fabricated timestamp. All 16 affected columns are now
-- clientDefault{} in Kotlin, which makes Exposed always write the column -- so these DDL defaults are
-- dormant. We drop them on the FISCAL tables anyway, where a future raw-SQL insert silently
-- inheriting a stale literal would corrupt audit-relevant data:
--   kassenbuch_header.created_at -> KB closing membership + BON_START/BON_ENDE
--   table_session.start_time     -> feeds a bill's BON_START
--   entry_log.created_at         -> the audit trail's own clock
-- The remaining 13 columns (employee/customer/timetracking/pfand/product/scheduler) keep their DDL
-- default: dormant after the Kotlin fix, and dropping them expands migration surface for non-fiscal data.
--
-- table_session.start_time and entry_log.created_at rows are left as-is: there is no independent
-- attested clock to realign them to, so overwriting them WOULD be fabrication. The DSFinV-K export
-- still tolerates already-frozen kassenbuch rows via the zbon_id-first selection (see
-- DsfinvkExportServiceImpl) as defense-in-depth.
--
-- kassenbuch_header.created_at IS realigned below (revised while V94 was still unreleased): the
-- frozen literal is the server boot instant, not a real creation time, whereas the TSE hardware log
-- timestamp is the cryptographically signed moment the entry was finalised -- a genuinely recorded
-- time, not a fabricated one. See the UPDATE after the DDL changes.
ALTER TABLE kassenbuch_header ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE table_session     ALTER COLUMN start_time DROP DEFAULT;
ALTER TABLE entry_log         ALTER COLUMN created_at DROP DEFAULT;

-- Realign frozen kassenbuch_header.created_at to the TSE-attested signature time.
-- Primary source: tse_transaction.tse_timestamp (Unix seconds from the TSE hardware = the
-- FinishTransaction log time). created_at is a timezone-less LocalDateTime stored in shop time, so
-- the epoch instant is converted to the shop-local wall clock via shop_info.shop_timezone (fallback
-- Europe/Berlin). Rows whose TSE transaction has no hardware timestamp fall back to end_time (the
-- server-clock finish, already shop-local). Rows with no linked TSE transaction -- or neither a
-- hardware timestamp nor an end_time -- keep their existing created_at.
UPDATE kassenbuch_header kh
SET created_at = COALESCE(
        to_timestamp(tt.tse_timestamp::double precision) AT TIME ZONE (
            SELECT COALESCE(NULLIF(si.shop_timezone, ''), 'Europe/Berlin')
            FROM shop_info si ORDER BY si.shop_id LIMIT 1
        ),
        tt.end_time
    )
FROM tse_transaction tt
WHERE kh.tse_transaction_id = tt.id
  AND (tt.tse_timestamp IS NOT NULL OR tt.end_time IS NOT NULL);

-- ---------------------------------------------------------------------------
-- [source: V95__zbon_stammdaten_snapshot.sql]
-- ---------------------------------------------------------------------------
-- Round-5 KABS-2: per-closing Stammdaten snapshot.
--
-- DSFinV-K Tz. 3.2 (p.23) treats the Stammdaten as belonging TO a Kassenabschluss ("für jeden
-- Kassenabschluss nur einmal gespeichert"). The export previously re-read the LIVE shop_info row
-- for every Z_NR, so editing the shop address or tax number changed the bytes of an already
-- delivered closing and attributed historical turnover to an identity that did not apply then.
--
-- All columns are NULLABLE on purpose. NULL means "no snapshot" and the export falls back to the
-- live row — exactly today's behaviour. stamm_name is the "snapshot present" discriminator.
-- stamm_plz is VARCHAR so the leading-zero pad (Dresden 01067) is captured once at closing time
-- instead of being re-derived from a lossy INTEGER on every export. stamm_land stays RAW; the ISO
-- alpha-3 mapping remains at the export seam, shared with the fallback path.

ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_name    VARCHAR(255);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_strasse VARCHAR(500);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_plz     VARCHAR(10);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_ort     VARCHAR(100);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_land    VARCHAR(100);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_stnr    VARCHAR(50);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_ustid   VARCHAR(15);

-- ---------------------------------------------------------------------------------------------
-- BACKFILL OF EXISTING CLOSINGS — GUARDED, and it SKIPS rather than aborts when the guard fails.
-- ---------------------------------------------------------------------------------------------
--
-- Basis: the operator states the shop master data has never been changed since the system went
-- into use. If that holds, the CURRENT shop_info values ARE the values that applied at every
-- historical closing, so stamping them is a faithful record rather than a fabrication — and it
-- makes every already-delivered closing re-export byte-stably from here on.
--
-- We do not take that on trust. The data cannot prove it either way: shop_info has no updated_at,
-- entry_log stores no old values (only "Updated shop info '<name>'"), and entry_log.created_at was
-- itself frozen until V94. So the operator's statement is encoded here as a CHECKABLE
-- PRECONDITION instead of an assumption:
--
--   * the backfill runs only if the audit trail contains NO SHOP_INFO/UPDATE entry
--     (ShopInfoServiceImpl.updateShopInfo logs ChangeEntityType.SHOP_INFO + LogOperation.UPDATE,
--     both stored as enum NAMES by EntryLogTable.enumerationByName)
--   * and only if the audit trail is demonstrably active — at least one SHOP_INFO row of any kind
--     must exist. An entirely empty entry_log cannot distinguish "never edited" from "never
--     logged", and "no rows found" must not be read as evidence of absence.
--
-- WHEN THE PRECONDITION FAILS THE MIGRATION SKIPS THE BACKFILL AND SUCCEEDS; it does not abort.
-- Rationale: aborting would leave a schema-only failure that blocks startup while fixing nothing —
-- the columns are additive and the export is correct without them. Skipping leaves the affected
-- rows NULL, which the export renders via the live-row fallback, i.e. byte-for-byte the behaviour
-- those closings already had. Fiscal data must never be fabricated: a NULL that honestly means
-- "not recorded" is strictly better than a confident wrong value silently stamped onto a closing
-- that was already handed to a Prüfer. Closings taken from now on are snapshotted at creation
-- regardless, so the gap is bounded and shrinks to nothing.
--
-- LPAD(zip_code::text, 5, '0') reproduces the export's padStart(5,'0') exactly, so a backfilled
-- snapshot emits the same CSV bytes that closing emitted before this migration — the backfill
-- freezes the value, it does not change it.
--
-- Idempotent: `stamm_name IS NULL` means a re-run touches nothing already snapshotted.

UPDATE zbon z
SET stamm_name    = s.shop_name,
    stamm_strasse = s.shop_address,
    stamm_plz     = LPAD(s.zip_code::text, 5, '0'),
    stamm_ort     = s.shop_city,
    stamm_land    = s.shop_country,
    stamm_stnr    = s.shop_tax_number,
    stamm_ustid   = s.ust_id_nr
FROM shop_info s
WHERE z.shop_info_id = s.shop_id
  AND z.stamm_name IS NULL
  -- precondition 1: the shop was never edited according to the audit trail
  AND NOT EXISTS (
        SELECT 1 FROM entry_log
        WHERE type = 'SHOP_INFO' AND operation = 'UPDATE'
  )
  -- precondition 2: the audit trail is actually active, so precondition 1 is evidence of
  -- absence rather than absence of evidence
  AND EXISTS (
        SELECT 1 FROM entry_log
        WHERE type = 'SHOP_INFO'
  );

-- ---------------------------------------------------------------------------
-- [source: V96__zbon_kassen_stammdaten_snapshot.sql]
-- ---------------------------------------------------------------------------
-- Round-7 C3: per-closing Stamm_Kassen / Stamm_Terminals snapshot.
--
-- DSFinV-K Tz. 3.2 (p.23) treats the Stammdaten as belonging TO a Kassenabschluss („Zur Vermeidung
-- von Redundanzen werden die Stammdaten für jeden Kassenabschluss …"). Round-5's V95 fixed the
-- CLOSING-level shop identity (cashpointclosing.csv, location.csv) and stopped one file short:
--
--   * cashregister.csv (Stamm_Kassen) still read the LIVE KASSEN_MELDUNG_CONFIG setting, and
--     ServerManager.updateKassenSwVersion rewrites its kasseSwVersion on EVERY server start — so a
--     plain binary upgrade changed the KASSE_SW_VERSION of every already-delivered closing;
--   * slaves.csv (Stamm_Terminals) still read the LIVE `terminals` table — so re-branding a
--     tablet, upgrading a client, or registering a new terminal rewrote historical closings and
--     made them claim terminals that did not exist when they were taken.
--
-- Either way a re-exported closed period yielded different bytes than what was delivered.
--
-- WHAT IS *NOT* SNAPSHOTTED, ON PURPOSE:
--   Z_KASSE_ID, Z_NR, Z_ERSTELLUNG and TERMINAL_ID are JOIN KEYS carried by every DSFinV-K file.
--   This implementation derives Z_KASSE_ID from kasseSeriennr — the same value KASSE_SERIENNR
--   carries — so freezing KASSE_SERIENNR per closing while Z_KASSE_ID kept reading the live config
--   would desynchronise the two and break every cross-file join. KASSE_BASISWAEH_CODE is a literal
--   ('EUR') with no live source to drift from, and KEINE_UST_ZUORDNUNG is already derived from the
--   closing's own rows.
--
-- Every zbon column added here is NULLABLE. NULL means "no snapshot" and the export falls back to
-- the live config
-- + live terminals — exactly today's behaviour. stamm_kasse_snapshot_at is the discriminator: a
-- DEDICATED marker rather than "any column is non-null", because a closing may legitimately have
-- had ZERO terminals, so presence of zbon_stamm_terminals rows cannot distinguish "snapshotted,
-- and there were none" from "never snapshotted".

ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_kasse_snapshot_at TIMESTAMP;
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_kasse_brand       VARCHAR(50);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_kasse_modell      VARCHAR(50);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_kasse_sw_brand    VARCHAR(50);
ALTER TABLE zbon ADD COLUMN IF NOT EXISTS stamm_kasse_sw_version  VARCHAR(50);

CREATE TABLE IF NOT EXISTS zbon_stamm_terminals (
    id                   BIGSERIAL PRIMARY KEY,
    zbon_id              BIGINT      NOT NULL REFERENCES zbon (zbon_id) ON DELETE CASCADE,
    terminal_id          VARCHAR(50) NOT NULL,
    terminal_brand       VARCHAR(50),
    terminal_modell      VARCHAR(50),
    terminal_seriennr    VARCHAR(70),
    terminal_sw_brand    VARCHAR(50)  NOT NULL,
    terminal_sw_version  VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS zbon_stamm_terminals_zbon_id ON zbon_stamm_terminals (zbon_id);

-- ---------------------------------------------------------------------------------------------
-- NO BACKFILL — deliberately, and this is not an oversight.
-- ---------------------------------------------------------------------------------------------
--
-- V95 could argue for a backfill because the values it stamped were provably the ones that had
-- applied: shop_info had never been edited (encoded as a checkable entry_log precondition), so the
-- current row WAS the historical row. Nothing of that kind holds here:
--
--   * kasseSwVersion is OVERWRITTEN on every server start. The current value is the version of the
--     binary running right now, and every earlier value is gone — the database cannot tell which
--     version signed a closing from six months ago. Stamping today's value would not freeze
--     history, it would fabricate it, and it would fabricate precisely the field a Prüfer uses to
--     tie a closing to a certified software state.
--   * the `terminals` table has no history either. It records lastSeenAt, not membership over
--     time, so which terminals existed at a past closing is unknowable. A backfill would assert a
--     terminal set that may be flatly wrong in both directions (terminals registered after the
--     closing stamped into it, terminals deleted since omitted).
--
-- Rule R11 applies in the other direction from V95: there is no presence check that could turn
-- "we have no record" into evidence, so the honest answer is to record nothing. A NULL renders
-- through the live-row fallback in DsfinvkExportServiceImpl — i.e. byte-for-byte what those
-- closings have already been exporting. Fiscal data must never be fabricated.
--
-- The gap is bounded and shrinks to nothing:
--   * closings taken from now on snapshot at creation (ZBonRepositoryImpl.createReport), and
--   * KassenStammdatenSnapshot.freezeUnsnapshottedClosings runs from
--     ServerManager.updateKassenSwVersion BEFORE the next startup rewrite, preserving what the
--     remaining un-snapshotted closings currently export at the last moment it is still knowable
--     (the same freeze-on-edit reasoning as round-6 C5 in ShopInfoServiceImpl.updateShopInfo).
--
-- Idempotent: every statement is IF NOT EXISTS and nothing is written to existing rows, so a
-- re-run is a no-op.

-- ---------------------------------------------------------------------------
-- [source: V97__zbon_stammdaten_plz_no_truncation.sql]
-- ---------------------------------------------------------------------------
-- Round-7 C8: repair PLZ snapshots that V95's backfill TRUNCATED.
--
-- V95 stamped `stamm_plz = LPAD(s.zip_code::text, 5, '0')` and its own header comment justified the
-- backfill on the grounds that "LPAD(zip_code::text, 5, '0') reproduces the export's
-- padStart(5,'0') exactly, so a backfilled snapshot emits the same CSV bytes that closing emitted
-- before this migration — the backfill freezes the value, it does not change it."
--
-- That claim is false for a zip_code longer than 5 digits. PostgreSQL's LPAD TRUNCATES when the
-- input exceeds the target width (`LPAD('123456', 5, '0') = '12345'`); Kotlin's `padStart(5,'0')`
-- — used by both writers of this value on the live path, `StammdatenSnapshot` at closing time and
-- the export's live-row fallback — only ever PADS and returns a longer input unchanged. So for
-- those rows the backfill did exactly what it promised not to do: it CHANGED the bytes of an
-- already-delivered Kassenabschluss (closing immutability), and it changed them into a different
-- but entirely valid-looking 5-digit PLZ.
--
-- REACHABLE, and not by accident: `shop_info.zip_code` is an INTEGER and
-- `InitialSetupDto.ShopSetup.validate()` explicitly ADMITS 3..10 digits
-- (`require(zipCode.toString().length in 3..10)`). A 6+-digit value is accepted by the setup
-- wizard's own validation, so it is ordinary input, not corrupt data.
--
-- PRESERVE, DO NOT TRUNCATE. A German PLZ is 5 digits, so a longer value is already wrong — but
-- silently shortening it FABRICATES a different, plausible PLZ and hands it to a Prüfer as the
-- address of the Kassenabschluss. Fiscal data must never be fabricated: an obviously-wrong value
-- that survives intact is auditable and correctable at the source; a silently invented one is
-- neither. Preserving also keeps the snapshot byte-identical to what the export already emitted
-- for that closing, which is the entire point of the snapshot. `stamm_plz` is VARCHAR(10), which
-- accommodates the full 10 digits validation permits.
--
-- V95 is committed and was verified against a real PostgreSQL; it is not edited. This migration
-- corrects its output instead.
--
-- SCOPE — only the rows V95 mangled. The predicate is the truncation SIGNATURE itself:
--   * `stamm_plz = LPAD(s.zip_code::text, 5, '0')`  — the row still carries exactly what V95 wrote
--     from the CURRENT shop_info row, so re-deriving from that same row is a repair, not a guess;
--   * `stamm_plz <> <the preserving expression>`    — non-empty only when zip_code exceeds 5
--     digits, so nothing shorter is touched.
-- A closing snapshotted at CREATION time already holds the un-truncated `padStart` value and so
-- fails the first clause; a shop whose zip_code changed after V95 ran also fails it, and is left
-- alone rather than re-stamped from master data that no longer applies. That self-guarding makes
-- V95's entry_log preconditions unnecessary here — re-asserting them would only add a way to skip
-- a repair that is provably safe.
--
-- Idempotent by construction: after the update `stamm_plz` no longer equals the LPAD form, so a
-- re-run matches no rows.

UPDATE zbon z
SET stamm_plz = CASE
        WHEN length(s.zip_code::text) >= 5 THEN s.zip_code::text
        ELSE LPAD(s.zip_code::text, 5, '0')
    END
FROM shop_info s
WHERE z.shop_info_id = s.shop_id
  AND z.stamm_plz IS NOT NULL
  AND z.stamm_plz = LPAD(s.zip_code::text, 5, '0')
  AND z.stamm_plz <> CASE
        WHEN length(s.zip_code::text) >= 5 THEN s.zip_code::text
        ELSE LPAD(s.zip_code::text, 5, '0')
    END;

