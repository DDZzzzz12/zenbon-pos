-- Scheduled tasks: user-configurable cron-style jobs with persisted history.

CREATE TABLE scheduled_jobs (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    type            TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    frequency       TEXT NOT NULL,
    time_of_day     TEXT NOT NULL,
    day_of_week     INT NULL,
    day_of_month    INT NULL,
    parameters_json TEXT NOT NULL DEFAULT '{}',
    timezone        TEXT NOT NULL DEFAULT 'Europe/Berlin',
    next_run_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_run_at     TIMESTAMP WITH TIME ZONE NULL,
    last_status     TEXT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT scheduled_jobs_frequency_chk
        CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT scheduled_jobs_weekly_chk
        CHECK ((frequency = 'WEEKLY') = (day_of_week IS NOT NULL)),
    CONSTRAINT scheduled_jobs_monthly_chk
        CHECK ((frequency = 'MONTHLY') = (day_of_month IS NOT NULL)),
    CONSTRAINT scheduled_jobs_dow_range_chk
        CHECK (day_of_week IS NULL OR (day_of_week BETWEEN 1 AND 7)),
    CONSTRAINT scheduled_jobs_dom_range_chk
        CHECK (day_of_month IS NULL OR day_of_month = -1 OR (day_of_month BETWEEN 1 AND 28))
);

CREATE INDEX idx_scheduled_jobs_due
    ON scheduled_jobs (enabled, next_run_at);

CREATE TABLE job_executions (
    id              UUID PRIMARY KEY,
    job_id          UUID NOT NULL REFERENCES scheduled_jobs(id) ON DELETE CASCADE,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE NULL,
    status          TEXT NOT NULL,
    trigger_source  TEXT NOT NULL,
    result_message  TEXT NULL,
    error_message   TEXT NULL,

    CONSTRAINT job_executions_status_chk
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT job_executions_trigger_chk
        CHECK (trigger_source IN ('SCHEDULED', 'MANUAL'))
);

CREATE INDEX idx_job_executions_job_started
    ON job_executions (job_id, started_at DESC);
