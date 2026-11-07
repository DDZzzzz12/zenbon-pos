-- Update check settings and the nightly job that drives them (release v1.1.0).
--
-- One row, enforced by a CHECK on a fixed id. The GitHub token is stored encrypted; the key
-- lives in the systemd EnvironmentFile, so this ciphertext is inert in a database backup.
--
-- There is deliberately no repo_owner/repo_name here. The repository is a property of the channel
-- (UpdateChannel carries the coordinates), so storing it separately would allow a row whose channel
-- and repository disagree -- which is exactly the defect found on 2026-09-04, where selecting the
-- internal channel left the public repository configured and the private channel unreachable.
-- api_base_url stays configurable for a GitHub Enterprise host; only the owner/name path is fixed.

CREATE TABLE update_settings (
    id                    SMALLINT PRIMARY KEY DEFAULT 1,
    channel               TEXT NOT NULL DEFAULT 'PUBLIC',
    api_base_url          TEXT NOT NULL DEFAULT 'https://api.github.com',
    auto_apply            BOOLEAN NOT NULL DEFAULT FALSE,
    token_ciphertext      TEXT NULL,
    token_nonce           TEXT NULL,
    token_set_at          TIMESTAMP WITH TIME ZONE NULL,
    last_checked_at       TIMESTAMP WITH TIME ZONE NULL,
    last_check_outcome    TEXT NULL,
    available_version     TEXT NULL,
    available_notes       TEXT NULL,
    available_asset_id    BIGINT NULL,
    staged_path           TEXT NULL,
    -- Why the last nightly check refused to install. `last_check_outcome` already says
    -- UPDATE_SKIPPED, but the *blocker* only existed in the summary string the scheduler logs, so
    -- the back office rendered "staged, ready to install" for a server that had been turned away
    -- every night. The reason gets its own column rather than being encoded into the outcome word:
    -- the outcome vocabulary is compared by equality on the client, and a compound value would
    -- break every one of those comparisons silently.
    --
    -- Nullable and unconstrained by design. It holds a `PreflightVerdict.Reason` name, and a client
    -- that meets a name it does not know renders "the till is not ready" rather than guessing --
    -- which is also why a CHECK constraint here would only turn a future reason into a failed write.
    last_blocked_reason   TEXT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT update_settings_singleton_chk CHECK (id = 1),
    CONSTRAINT update_settings_channel_chk CHECK (channel IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT update_settings_token_pair_chk
        CHECK ((token_ciphertext IS NULL) = (token_nonce IS NULL))
);

INSERT INTO update_settings (id) VALUES (1);
