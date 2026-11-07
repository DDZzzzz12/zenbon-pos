-- Problem-report settings (in-app GitHub issue reporting).
--
-- One row, enforced by a CHECK on a fixed id. owner/repo/is_public are NULL while the operator has
-- not overridden them, meaning "follow the update channel" (PRIVATE -> DDZzzzz12/POS-KMP,
-- PUBLIC -> DDZzzzz12/zenbon-pos). The GitHub token is sealed with the same host key as the update
-- token (UPDATE_TOKEN_KEY), so this ciphertext is inert in a database backup restored elsewhere.
-- use_update_token is the Administrator's explicit choice to file reports with the update feature's
-- token instead of this row's own; the service then sends it only to the update source's API host.

CREATE TABLE issue_report_settings (
    id               SMALLINT PRIMARY KEY DEFAULT 1,
    owner            TEXT NULL,
    repo             TEXT NULL,
    api_base_url     TEXT NOT NULL DEFAULT 'https://api.github.com',
    is_public        BOOLEAN NULL,
    use_update_token BOOLEAN NOT NULL DEFAULT FALSE,
    token_ciphertext TEXT NULL,
    token_nonce      TEXT NULL,
    token_set_at     TIMESTAMP WITH TIME ZONE NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT issue_report_settings_singleton_chk CHECK (id = 1),
    CONSTRAINT issue_report_settings_token_pair_chk CHECK (
        (token_ciphertext IS NULL AND token_nonce IS NULL AND token_set_at IS NULL)
        OR (token_ciphertext IS NOT NULL AND token_nonce IS NOT NULL AND token_set_at IS NOT NULL)
    )
);

INSERT INTO issue_report_settings (id) VALUES (1);

-- ---------------------------------------------------------------------------------------------
-- Capability grants for the two new capabilities.
--
-- A fresh install seeds permission_group_capability from CapabilityBundles in SchemaManager and
-- picks these up on its own; an upgraded install is seeded only by V85, which has shipped and is
-- frozen, so the grants arrive here. Must equal CapabilityBundles (SeededGrantsMatchBundlesTest):
--   ADMIN   = every capability  -> ISSUE_REPORT_CREATE, ISSUE_REPORT_MANAGE
--   MANAGER                     -> ISSUE_REPORT_CREATE
-- Selecting by a group code that does not match would insert nothing silently, hence the assertion.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permission_group_capability (group_id, capability)
SELECT id, cap FROM permission_group CROSS JOIN (VALUES
    ('ISSUE_REPORT_CREATE'), ('ISSUE_REPORT_MANAGE')
) AS v(cap) WHERE code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO permission_group_capability (group_id, capability)
SELECT id, 'ISSUE_REPORT_CREATE' FROM permission_group WHERE code = 'MANAGER'
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM permission_group_capability c JOIN permission_group g ON g.id = c.group_id
         WHERE g.code = 'ADMIN' AND c.capability IN ('ISSUE_REPORT_CREATE', 'ISSUE_REPORT_MANAGE')) <> 2 THEN
        RAISE EXCEPTION 'ADMIN did not receive both issue-report capabilities';
    END IF;
    IF (SELECT COUNT(*) FROM permission_group_capability c JOIN permission_group g ON g.id = c.group_id
         WHERE g.code = 'MANAGER' AND c.capability = 'ISSUE_REPORT_CREATE') <> 1 THEN
        RAISE EXCEPTION 'MANAGER did not receive ISSUE_REPORT_CREATE';
    END IF;
END $$;
