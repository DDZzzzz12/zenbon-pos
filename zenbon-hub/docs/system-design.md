# ZenBon Hub — System Design

| | |
|---|---|
| **Status** | Draft v0.2 — design only, nothing implemented. v0.2: the mail plugin changed from automatic invoice intake to an operator-driven mail client (§11.2) |
| **Date** | 2026-10-27 |
| **Owner** | ZenBon platform |
| **Audience** | Whoever builds or reviews the hub; this document is the guide for the project |
| **Related** | [`docs/superpowers/specs/2026-08-25-n8n-invoice-intake-design.md`](../../docs/superpowers/specs/2026-08-25-n8n-invoice-intake-design.md) (superseded in transport by this design), [`2026-08-12-banking-statement-parsing-design.md`](../../docs/superpowers/specs/2026-08-12-banking-statement-parsing-design.md) (what pos-server does with a statement once it has one) |

---

## 1. Purpose

ZenBon Hub is a small, optional, separately deployed **integration service**. It talks to the outside
world — a bank over FinTS, a mailbox over IMAP, later AI services — and hands the resulting artifacts
to pos-server through pos-server's existing document/upload surface.

**The hub is a courier, not a system of record.**

- It owns no business data. pos-server's Postgres is the only source of truth for documents,
  statements, invoices and bookings. The mail cache (§6.3) is **not** business data: the mailbox on the
  mail server stays the source of truth and the cache can be rebuilt from it.
- It is never on the till's critical path. A bank outage, a stuck TAN or a crashed hub must not
  affect selling, printing or fiscal signing.
- It owns two kinds of state: *operational* state (credentials, cursors, retries, logs) and a rebuildable
  cache of mailbox contents.

### Goals (v1)

1. Fetch bank statements from one or more FinTS accounts and deliver them to pos-server.
2. Act as the backend of a mail client for one or more mailboxes: sync mail into a local cache, send mail, and let an
   operator **manually** push a chosen attachment to pos-server. Nothing is imported automatically.
3. Let an operator set up, monitor and approve (TAN) these connections, and read and send mail, from adminApp.
4. Make adding a further capability (AI classification, other feeds) a matter of adding a plugin, not editing the core.

### Non-goals

- Parsing statements or invoices. `:banking` and `:einvoice` already do this in Kotlin; the hub never
  interprets what it carries. (One exception: it needs the file's *format* only to choose a filename/MIME type.)
- Booking, matching, reconciliation or any accounting logic.
- Automatic mail processing: no rules, no auto-filing, no auto-import of attachments, no auto-replies. Every transfer
  from a mailbox to pos-server is an explicit operator action.
- A full-featured mail client: no calendar, contacts, server-side rules or offline composition. Reading, searching,
  replying, sending and pushing attachments only.
- Payment initiation (SEPA credit transfers, direct debits). **Read-only banking.** This is a hard line:
  a service that can move money has a different threat model and is out of scope until explicitly designed.
- Multi-tenant hosting. One hub serves one pos-server.
- Third-party (untrusted) plugins. See §5.4.
- High availability. A single instance is acceptable; §9 explains why.

---

## 2. Context and verified starting facts

Facts checked against the repo on 2026-10-27:

| Fact | Consequence |
|---|---|
| `:banking` parses CAMT.053 and MT940, with overlap merge and balance invariants; re-uploading the same bytes is rejected by the document layer. | The hub can safely re-fetch overlapping windows; duplicates are pos-server's problem and already solved. |
| The n8n intake design specifies `POST /api/integration/documents/inbound` (API-key auth, `INTEGRATION` uploader, optional `source/sender/subject/receivedAt`). | **That endpoint is not built** — no `INTEGRATION` uploader or `inbound` route exists in `pos-server`, and there is no `deploy/n8n/` folder. The hub depends on it; building it is the first pos-server task (§12). |
| The banking-parsing design (2026-08-12) records that `FileStorageHelper.isValidFile` accepted only PDF/JPEG/PNG/XML magic bytes, so a plain-text MT940 `.sta` was rejected with 415. | **Unverified whether still true on `dev`.** If a bank only offers MT940 the hub is blocked until pos-server accepts it. Verify before relying on MT940. CAMT.053 (XML) is unaffected. |
| An earlier FinTS session exists on the maintainer's Mac, not the workstation. | Its findings (bank, library version, TAN method, product ID, whether a second fetch needed no TAN) are an **input to the Phase 0 spike**, not yet available here. |

### Assumptions (to be proven, riskiest first)

| # | Assumption | If wrong | Cheapest test |
|---|---|---|---|
| A1 | After the initial SCA, scheduled history fetches succeed without a TAN until the bank's exemption window (≈90 days) lapses. | The "nightly sync" degrades to "operator approves every sync"; value drops sharply. | Phase 0 spike on a real account: fetch, wait, fetch again. |
| A2 | The FinTS library exposes the bank's **raw** statement (CAMT XML via HKCAZ, or raw MT940) rather than only parsed objects. | The hub would have to re-serialise, which risks silently diverging from what the bank sent. | Spike: inspect `get_transactions_xml` and the raw HIKAZ response. |
| A3 | The bank supports HKCAZ (CAMT). | Falls back to MT940 → depends on the 415 question above. | Spike: check the bank's BPD for HKCAZ. |
| A4 | Operators accept approving a TAN in adminApp on a cadence of ~quarterly. | Adoption fails regardless of engineering. | Ask the first real operator. |
| A5 | The target mailboxes are reachable over IMAP/SMTP with a credential the hub can hold (a password, or an OAuth2 refresh token). | Gmail and Microsoft 365 need OAuth2 from day one, and the operator must register an OAuth client (§11.2). | Try the real providers at the start of Phase 4; Microsoft 365 has disabled basic auth. |

---

## 3. Requirements

### Functional

| ID | Requirement |
|---|---|
| F1 | Register a bank connection (bank, user id, TAN method, accounts) and store its credentials encrypted. |
| F2 | Sync an account on a schedule and on demand; deliver CAMT/MT940 artifacts to pos-server. |
| F3 | When the bank demands a TAN, suspend the run, expose the challenge, resume when the operator answers. |
| F4 | Sync one or more mailboxes into a local cache (headers, text body, attachment metadata) and search it. |
| F4a | Send mail over SMTP, including replies, and keep a copy in the account's Sent folder. |
| F4b | Operator manually pushes a chosen attachment to pos-server; the hub does nothing to a mail on its own. |
| F5 | Guarantee **at-least-once** delivery to pos-server with retries; duplicates are harmless downstream. |
| F6 | Expose connection/run/health status to pos-server for display in adminApp. |
| F7 | Enable/disable plugins and individual connections at runtime without redeploying. |
| F8 | Keep an audit trail of every outbound bank dialog and every delivery. |

### Non-functional

| Area | Target |
|---|---|
| **Scale** | Handful of accounts, handful of mailboxes, tens of mails and a few statements per day. Trivially single-process. |
| **Latency** | None user-facing, except TAN prompts: challenge visible in adminApp within ~2 s of the bank issuing it. |
| **Availability** | Best-effort. Downtime delays imports; it loses nothing (§9). |
| **Durability** | Undelivered artifacts survive a restart (outbox). Losing the SQLite file loses connections/credentials, **not business data**. |
| **Security** | Bank PIN and mailbox secrets encrypted at rest; never logged; hub reachable only from pos-server (§8). |
| **Operability** | One container or one systemd unit; one env file; `/health` that tells the truth. |
| **Portability** | Runs beside pos-server on the same host, or on a separate VPS by editing one URL. |

### Constraints

- Python 3.12 (FinTS ecosystem), SQLite (decided), single deploy unit.
- Must not require changes to pos-server beyond: the inbound endpoint, an admin proxy, and a capability (§7.2).
- The public `zenbon-pos` release must remain free of the proprietary Swissbit SDK — unaffected, but any new
  dependency still passes a licence review (the FinTS library is believed to be **LGPL-3.0**; confirm before shipping).
- Repo rules that carry over: develop on `dev`, single commit when a piece of work is finished, never carry money in
  floats (the hub mostly forwards files, but any amount it displays or logs is a decimal string).

---

## 4. High-level architecture

```
                         ┌────────────────────────── operator ──────────────────────────┐
                         │                                                              │
                    adminApp (browser)                                           bank app / SMS
                         │  JWT + capability                                            │
                         ▼                                                              │
   ┌───────────────────────────────────┐   X-API-Key (inbound)                          │
   │            pos-server             │◄────────────────────────────┐                  │
   │  Postgres = source of truth       │                             │                  │
   │  /api/integration/.../inbound     │                             │                  │
   │  /api/bank-connections/* (proxy)  │──── HUB_API_KEY ───┐        │                  │
   └───────────────────────────────────┘                    ▼        │                  │
                                              ┌─────────────────────────────────────┐   │
                                              │            ZenBon Hub               │   │
                                              │  ┌───────────────────────────────┐  │   │
                                              │  │ CORE                          │  │   │
                                              │  │  registry · scheduler · outbox│  │   │
                                              │  │  secrets  · pos_client · db   │  │   │
                                              │  │  http api · health · audit    │  │   │
                                              │  └──────┬─────────────┬──────────┘  │   │
                                              │   plugin│fints        │mail   (ai…) │   │
                                              │  ┌──────▼──────┐ ┌────▼────────┐    │   │
                                              │  │ FinTS 3.0   │ │ IMAP · SMTP │    │   │
                                              │  └──────┬──────┘ └────┬────────┘    │   │
                                              │         │ SQLite (WAL) │            │   │
                                              └─────────┼─────────────┼─────────────┘   │
                                                        ▼             ▼                 │
                                                  bank FinTS      mail server ──────────┘
                                                  endpoint        (IMAPS)         TAN approval
```

### Data flow — statement

1. Scheduler fires `fints.sync` for an account (or the operator triggers it).
2. Plugin opens a FinTS dialog; bank may demand a TAN → run enters `awaiting_tan` (§7.4).
3. Plugin receives the statement (CAMT XML preferred), hands it to the core `Outbox.submit(artifact)`.
4. Outbox persists bytes + metadata, then the delivery worker POSTs to pos-server's inbound endpoint.
5. pos-server stores the document, detects the format from magic bytes, runs `:banking`, merges overlaps. Hub marks the artifact `delivered` and deletes the bytes.

### Data flow — email

Three separate flows; none of them moves anything to pos-server by itself.

1. **Sync:** scheduler or operator refresh → IMAP → cache (§6.3). No artifact, no outbox.
2. **Send:** adminApp → pos-server proxy → hub queues a `mail_outgoing` row → SMTP → copy appended to Sent.
3. **Push attachment (manual):** operator picks an attachment in adminApp → proxy → hub reads the bytes (cache, else
   IMAP) → `Outbox.submit(source=mail, metadata={account, message-id, sender, subject, date})` → delivery as in steps 4–5 above.

### Key principle: one outbound door

Every plugin delivers through the **same core outbox**. There is exactly one module (`pos_client`) that talks to
pos-server. That gives retry, backoff, auth, logging and the "treat duplicate as success" rule in one place.

---

## 5. Core and plugin model

### 5.1 Division of responsibility

| Core (never in a plugin) | Plugin |
|---|---|
| `pos_client` — the only path to pos-server | HTTP routes (an `APIRouter` mounted at `/v1/<id>/`) |
| Secret store, encryption-key handling | Scheduled jobs (registered through the core scheduler) |
| Scheduler, `job_run` log, retry policy | Its own tables, prefixed `<id>_` |
| Outbox + delivery worker | Config schema (validated and stored by core) |
| SQLite connection, migrator | A health check and start/stop lifecycle |
| API auth, request logging, audit | Domain logic (FinTS dialogs, IMAP handling) |
| Plugin registry and enable/disable state | |

### 5.2 Plugin contract (provisional)

```python
class HubPlugin(Protocol):
    id: str                       # "fints", "mail"
    version: str
    def migrations(self) -> Path: ...                   # dir of NNN_*.sql, applied under namespace `id`
    def config_schema(self) -> type[BaseModel]: ...
    def routes(self) -> APIRouter: ...
    def register_jobs(self, scheduler: Scheduler) -> None: ...
    async def start(self, ctx: PluginContext) -> None: ...
    async def stop(self) -> None: ...
    async def health(self) -> Health: ...
```

`PluginContext` is the plugin's **only** window to the core: scoped `outbox`, `secrets` (namespaced to the
plugin), `db` (restricted to the plugin's table prefix by convention and tested), `config`, `clock`, `log`.

> **Provisional on purpose.** The first interface is always wrong. The contract is extracted from what the FinTS
> and mail plugins actually need, in Phase 2, and frozen only then. Do not design `ai/` hooks (e.g. an event bus
> for "message synced → classify") until the AI plugin exists to dictate them.

### 5.3 Discovery and lifecycle

- **Discovery:** Python entry-point group `zenbon.hub.plugins` (first-party plugins live in the same repo and are
  registered in `pyproject.toml`).
- **Enable/disable:** `plugin` table row, flipped through the API; takes effect on the plugin's next
  start. A disabled plugin's routes return 404 and its jobs are not scheduled.
- **Startup order:** migrate core → discover → for each enabled plugin: migrate its namespace → validate config →
  `start`. A plugin that fails to start is marked `failed` with the error; **it does not stop the hub or other plugins.**
- **No hot-install.** Adding a plugin = install package + restart (seconds). Hot loading/unloading is explicitly
  rejected as cost without benefit for a service that syncs a few times a day.

### 5.4 Trust boundary

Plugins run **in-process** and can reach the secret store, which holds bank PINs. Therefore:

- Plugins are **first-party / reviewed only**. The doc and README must say so.
- Supporting untrusted plugins would require a process (or container) per plugin talking over a local API with
  scoped credentials — a different project; not planned.
- Plugins may not import each other. Cross-plugin interaction, when it becomes necessary, goes through a
  core-owned mechanism that does not exist yet (see §13).

---

## 6. Data model (SQLite)

SQLite in **WAL** mode, `foreign_keys=ON`, single writer (one process, one uvicorn worker). Access through a thin
repository layer over the stdlib `sqlite3`; revisit SQLAlchemy Core only if queries grow beyond what a page of SQL covers.

**Migrations:** plain, numbered `.sql` files — consistent with the repo's Flyway habit — one directory per
namespace (`core/`, `plugins/fints/`, `plugins/mail/`), tracked in `schema_migration(namespace, version, applied_at, checksum)`.
Never edit an applied migration. Removing a plugin never touches another namespace.

### 6.1 Core tables

```sql
CREATE TABLE schema_migration (
  namespace   TEXT NOT NULL,
  version     INTEGER NOT NULL,
  checksum    TEXT NOT NULL,
  applied_at  TEXT NOT NULL,
  PRIMARY KEY (namespace, version)
);

CREATE TABLE plugin (
  id          TEXT PRIMARY KEY,           -- 'fints'
  enabled     INTEGER NOT NULL DEFAULT 0,
  config_json TEXT NOT NULL DEFAULT '{}',
  status      TEXT NOT NULL DEFAULT 'stopped',   -- stopped|running|failed
  last_error  TEXT,
  updated_at  TEXT NOT NULL
);

CREATE TABLE secret (                     -- encrypted values only
  namespace   TEXT NOT NULL,              -- plugin id
  name        TEXT NOT NULL,              -- e.g. 'connection:3:pin'
  ciphertext  BLOB NOT NULL,              -- AES-GCM / Fernet, key = HUB_SECRET_KEY
  key_version INTEGER NOT NULL DEFAULT 1,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (namespace, name)
);

CREATE TABLE outbox (                     -- artifacts awaiting delivery to pos-server
  id             INTEGER PRIMARY KEY,
  source         TEXT NOT NULL,           -- 'fints' | 'mail'
  sha256         TEXT NOT NULL,
  filename       TEXT NOT NULL,
  media_type     TEXT NOT NULL,
  size_bytes     INTEGER NOT NULL,
  payload_path   TEXT,                    -- file under data/outbox/; NULL once delivered
  metadata_json  TEXT NOT NULL DEFAULT '{}',   -- sender, subject, receivedAt, account ref…
  status         TEXT NOT NULL DEFAULT 'pending',  -- pending|delivered|failed
  attempts       INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TEXT NOT NULL,
  last_error     TEXT,
  pos_document_id TEXT,
  created_at     TEXT NOT NULL,
  delivered_at   TEXT,
  UNIQUE (source, sha256)                 -- the hub never queues the same bytes twice per source
);
CREATE INDEX outbox_due ON outbox(status, next_attempt_at);

CREATE TABLE job_run (
  id          INTEGER PRIMARY KEY,
  plugin      TEXT NOT NULL,
  job         TEXT NOT NULL,              -- 'fints.sync'
  subject     TEXT,                       -- connection/mailbox id
  state       TEXT NOT NULL,              -- queued|running|awaiting_tan|succeeded|failed|expired
  started_at  TEXT NOT NULL,
  finished_at TEXT,
  summary_json TEXT,                      -- counts, never contents
  error       TEXT
);

CREATE TABLE audit_event (                -- append-only; no UPDATE/DELETE issued by the app
  id          INTEGER PRIMARY KEY,
  at          TEXT NOT NULL,
  actor       TEXT NOT NULL,              -- 'scheduler' | 'pos-server:<user>' | 'plugin:<id>'
  action      TEXT NOT NULL,              -- 'fints.dialog.open', 'delivery.ok', 'secret.write' …
  subject     TEXT,
  detail_json TEXT
);
```

### 6.2 `fints` tables

```sql
CREATE TABLE fints_connection (
  id              INTEGER PRIMARY KEY,
  label           TEXT NOT NULL,
  bank_code       TEXT NOT NULL,          -- BLZ
  endpoint_url    TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  tan_method      TEXT,                   -- chosen security function, e.g. '940'
  tan_medium      TEXT,
  pin_storage     TEXT NOT NULL DEFAULT 'stored',   -- stored | ask_each_time
  state           TEXT NOT NULL DEFAULT 'new',      -- new|ready|needs_attention|disabled
  disabled_reason TEXT,                   -- 'auth_failed' etc.; blocks automatic retry
  client_state    BLOB,                   -- serialised library state (BPD/UPD, system id), encrypted
  sca_valid_until TEXT,                   -- best estimate of when the history exemption lapses
  created_at      TEXT NOT NULL
);

CREATE TABLE fints_account (
  id            INTEGER PRIMARY KEY,
  connection_id INTEGER NOT NULL REFERENCES fints_connection(id) ON DELETE CASCADE,
  iban          TEXT NOT NULL,
  label         TEXT,
  sync_enabled  INTEGER NOT NULL DEFAULT 1,
  format        TEXT NOT NULL DEFAULT 'camt',     -- camt | mt940, from BPD capability
  last_booking_date TEXT,                 -- sync cursor
  last_success_at TEXT,
  UNIQUE (connection_id, iban)
);

CREATE TABLE fints_pending_tan (
  id           INTEGER PRIMARY KEY,
  job_run_id   INTEGER NOT NULL REFERENCES job_run(id),
  connection_id INTEGER NOT NULL REFERENCES fints_connection(id),
  kind         TEXT NOT NULL,             -- 'decoupled' | 'typed'
  challenge    TEXT,                      -- human text from the bank; no TAN material
  expires_at   TEXT NOT NULL,
  resolved_at  TEXT
);
```

### 6.3 `mail` tables

The mailbox on the server stays the source of truth. These tables are a **rebuildable cache** plus a queue of outgoing
messages. Secrets are in the core `secret` table under `mail:<account id>:password`, `…:oauth_refresh_token`,
`…:oauth_client_secret`.

```sql
CREATE TABLE mail_account (
  id             INTEGER PRIMARY KEY,
  label          TEXT NOT NULL,
  address        TEXT NOT NULL,
  imap_host      TEXT NOT NULL,
  imap_port      INTEGER NOT NULL DEFAULT 993,
  imap_security  TEXT NOT NULL DEFAULT 'tls',        -- tls | starttls (never plaintext)
  smtp_host      TEXT NOT NULL,
  smtp_port      INTEGER NOT NULL DEFAULT 587,
  smtp_security  TEXT NOT NULL DEFAULT 'starttls',   -- starttls | tls
  username       TEXT NOT NULL,
  auth_method    TEXT NOT NULL DEFAULT 'password',   -- password | oauth2
  oauth_provider TEXT,                               -- google | microsoft | generic; NULL for password
  sync_enabled   INTEGER NOT NULL DEFAULT 1,
  poll_seconds   INTEGER NOT NULL DEFAULT 300,
  sync_since     TEXT,                               -- cache horizon, e.g. 90 days back
  retention_days INTEGER,                            -- NULL = keep until the account is removed
  state          TEXT NOT NULL DEFAULT 'ready',      -- ready | needs_attention | disabled
  disabled_reason TEXT,                              -- 'auth_failed', 'oauth_revoked' … blocks retry
  created_at     TEXT NOT NULL
);

CREATE TABLE mail_folder (
  id            INTEGER PRIMARY KEY,
  account_id    INTEGER NOT NULL REFERENCES mail_account(id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'other',       -- inbox | sent | drafts | trash | other
  uidvalidity   INTEGER,
  highestmodseq INTEGER,                             -- when the server supports CONDSTORE
  sync_enabled  INTEGER NOT NULL DEFAULT 1,
  last_synced_at TEXT,
  UNIQUE (account_id, name)
);

CREATE TABLE mail_message (
  id            INTEGER PRIMARY KEY,
  folder_id     INTEGER NOT NULL REFERENCES mail_folder(id) ON DELETE CASCADE,
  uid           INTEGER NOT NULL,
  uidvalidity   INTEGER NOT NULL,
  message_id    TEXT,
  in_reply_to   TEXT,
  thread_key    TEXT,
  from_addr     TEXT,
  to_json       TEXT,
  cc_json       TEXT,
  subject       TEXT,
  sent_at       TEXT,
  flags_json    TEXT NOT NULL DEFAULT '[]',
  snippet       TEXT,
  body_text     TEXT,
  body_html     TEXT,                                -- raw; sanitised only at render time (§8.1)
  size_bytes    INTEGER,
  has_attachments INTEGER NOT NULL DEFAULT 0,
  UNIQUE (folder_id, uidvalidity, uid)
);
CREATE INDEX mail_message_by_date ON mail_message(folder_id, sent_at DESC);
CREATE INDEX mail_message_by_msgid ON mail_message(message_id);
CREATE VIRTUAL TABLE mail_message_fts USING fts5(
  subject, from_addr, body_text, content='mail_message', content_rowid='id'
);

CREATE TABLE mail_attachment (
  id            INTEGER PRIMARY KEY,
  message_id    INTEGER NOT NULL REFERENCES mail_message(id) ON DELETE CASCADE,
  part_id       TEXT NOT NULL,                       -- IMAP BODYSTRUCTURE part
  filename      TEXT,
  media_type    TEXT,
  size_bytes    INTEGER,
  sha256        TEXT,                                -- set once the bytes have been fetched
  cache_path    TEXT,                                -- NULL until fetched; size-capped cache, evictable
  push_state    TEXT NOT NULL DEFAULT 'none',        -- none | queued | delivered | failed
  outbox_id     INTEGER REFERENCES outbox(id),
  pos_document_id TEXT,
  pushed_by     TEXT,
  pushed_at     TEXT,
  UNIQUE (message_id, part_id)
);

CREATE TABLE mail_outgoing (
  id              INTEGER PRIMARY KEY,
  account_id      INTEGER NOT NULL REFERENCES mail_account(id),
  reply_to_message_id INTEGER REFERENCES mail_message(id),
  envelope_json   TEXT NOT NULL,                     -- from, to, cc, bcc
  subject         TEXT NOT NULL,
  body_text       TEXT NOT NULL,
  body_html       TEXT,
  attachments_json TEXT NOT NULL DEFAULT '[]',
  state           TEXT NOT NULL DEFAULT 'queued',    -- queued | sending | sent | unknown | failed
  smtp_message_id TEXT,
  error           TEXT,
  queued_by       TEXT NOT NULL,                     -- the operator, from the pos-server proxy
  created_at      TEXT NOT NULL,
  sent_at         TEXT,
  sent_copy_appended INTEGER NOT NULL DEFAULT 0
);
```

Sizing note: a cache of headers, text bodies and attachment *metadata* is small; attachment **bytes** are fetched on
demand into a size-capped, evictable cache, never mirrored wholesale.

### 6.4 Rule of thumb

> If you can delete the SQLite file and recover everything except credentials and connection setup, the design is
> right. Artifacts already delivered live in pos-server; cursors rebuild because pos-server de-duplicates; the mail
> cache re-syncs from the mailboxes. The one thing that must survive in two places is the fact that an attachment was
> pushed — it is in the hub (`mail_attachment.push_state`) **and** in the pos-server document's metadata.

---

## 7. Interfaces

### 7.1 Hub → pos-server (delivery)

`POST {POS_SERVER_URL}/api/integration/documents/inbound` — as specified in the n8n design:

- Header `X-API-Key: <integration key>`; multipart `file` plus `source`, `sender`, `subject`, `receivedAt`
  (and, for banking, an account reference in `metadata`).
- **Delivery semantics:** at-least-once. The outbox retries with exponential backoff (base 30 s, factor 2, cap 1 h,
  jitter), up to a configured age (default 14 days) before flipping to `failed` and raising an operator-visible alert.
- **Status handling:** `200` → delivered. `4xx` other than `408/429` → `failed` immediately (a bad payload will not
  improve). `5xx`, timeouts, connection refused → retry. A "duplicate bytes" refusal — whatever status pos-server
  uses; **contract to be pinned when the endpoint is built** — counts as **delivered**.
- The endpoint is disabled (404) in pos-server until an integration key is configured. The hub treats 404 as a
  configuration error, not a retryable one, and reports it in `/health`.

### 7.2 pos-server → hub (administration)

adminApp never talks to the hub. adminApp → **pos-server** (JWT, capability check, audit) → hub:

```
adminApp ──JWT──► pos-server /api/bank-connections/**  ──Bearer HUB_API_KEY──► hub /v1/fints/**
```

Consequences:
- Existing `RoutePermissions` and audit trail cover bank connections for free.
- A **new capability** is needed (working name `BANK_CONNECTION_MANAGE`); reading status uses a lower one.
  Registered in `RoutePermissions` per the repo rule, enforced in route *and* service (guards at both levels).
- If the hub is down, only the banking screen degrades; pos-server answers "hub unavailable" (503 problem+json).
- The hub accepts **only** its own bearer key, binds to localhost/LAN/WireGuard, and has no public exposure.

### 7.3 Hub HTTP API (v1, sketch)

| Method & path | Purpose |
|---|---|
| `GET /health` | Overall + per-plugin + outbox backlog + oldest undelivered age. No secrets. |
| `GET /v1/plugins` · `POST /v1/plugins/{id}/enable` · `…/disable` | Runtime enable/disable (F7). |
| `GET/POST/PATCH/DELETE /v1/fints/connections` | CRUD; PIN accepted write-only. |
| `POST /v1/fints/connections/{id}/sync` | On-demand sync → returns `job_run` id. |
| `GET /v1/fints/runs/{id}` | State incl. `awaiting_tan` and the challenge text. |
| `POST /v1/fints/runs/{id}/tan` | Submit a typed TAN. Decoupled TANs need no call; the hub polls. |
| `GET/POST/PATCH/DELETE /v1/mail/accounts` | CRUD; password/OAuth secrets write-only. |
| `GET /v1/mail/oauth/{provider}/authorize` · `POST /v1/mail/oauth/callback` | OAuth2 authorization-code + PKCE (§11.2). |
| `POST /v1/mail/accounts/{id}/sync` | On-demand refresh. |
| `GET /v1/mail/accounts/{id}/folders` · `GET /v1/mail/messages?folder=&q=` | List folders; list/search cached mail. |
| `GET /v1/mail/messages/{id}` · `GET /v1/mail/attachments/{id}/content` | Message detail; attachment bytes (fetched on demand). |
| `POST /v1/mail/send` · `GET /v1/mail/outgoing/{id}` | Queue a message; inspect its state. |
| `POST /v1/mail/attachments/{id}/push` | **Manual** push of one attachment to pos-server via the outbox. |
| `GET /v1/outbox?status=` · `POST /v1/outbox/{id}/retry` | Inspect/retry deliveries. |

Errors use RFC 7807 `application/problem+json`, matching pos-server so the proxy can pass them through unchanged.

### 7.4 FinTS TAN state machine

```
queued ──► running ──► succeeded
              │  ▲
   bank asks  │  │ TAN accepted / decoupled approved
   for TAN    ▼  │
          awaiting_tan ──► expired     (challenge timed out, default 5 min)
              │
              └──► failed              (wrong TAN, bank error)
```

- **Decoupled (pushTAN/SecureGo):** adminApp only *displays* "approve in your banking app"; the hub polls the bank
  until approved or expired. **No TAN crosses the wire.** Prefer this wherever the bank offers it.
- **Typed (chipTAN/SMS):** adminApp shows the challenge, the operator types the code, pos-server forwards it.
- The suspended dialog's library state is serialised (`client_state`, encrypted) so a TAN can be submitted after a
  hub restart, within the challenge lifetime. *(Library capability to be confirmed in the spike.)*
- A scheduled run that needs a TAN creates a `fints_pending_tan` and stops there. It does **not** retry on its own.

---

## 8. Security

### 8.1 Assets and threats

| Asset | Threat | Control |
|---|---|---|
| Bank PIN | Theft from disk/backup | Encrypted at rest (AES-GCM or Fernet) with `HUB_SECRET_KEY` from the **environment**, never stored in the DB or repo. Option `ask_each_time` stores nothing. |
| Bank account lockout | Repeated wrong-PIN attempts lock the user's online banking | **Never auto-retry an authentication failure.** First `auth_failed` sets `state=needs_attention`, `disabled_reason='auth_failed'`; only an operator action clears it. |
| TAN | Interception | Single-use and short-lived; decoupled flow carries none; typed TAN is held in memory only, never logged or persisted. |
| Hub API | Unauthorised use (it can trigger bank dialogs) | Bearer key, network-restricted, reachable only from pos-server; constant-time comparison. |
| Integration key | Leak lets anyone upload documents | Lives only in hub env; pos-server returns 404 when unset. |
| Logs | Secrets/PII in log lines | Structured logging with a redaction filter; IBANs masked to first 4 + last 4; amounts and statement contents never logged. |
| Statement files on disk | Sensitive financial data lingering | Payload file deleted on delivery; failed ones kept only to `failed` retention (default 14 days), then purged. |
| Read-only guarantee | A bug or plugin initiates a payment | The FinTS plugin imports only read operations through a thin wrapper; **no** transfer/DD segments are reachable. Enforced by a test that fails if the wrapper exposes them. |
| Mail content | Mail bodies are personal data (GDPR) and may carry anything | Never logged (no subject, address or body in logs); per-account `retention_days`; removing an account cascades its cache; access gated by capability in pos-server, per account (Q10). The SQLite file is **not** encrypted by the hub — disk encryption is expected, or SQLCipher if Q11 says otherwise. |
| OAuth tokens | Refresh token theft = standing mailbox access | Stored only in the encrypted `secret` table; access tokens held in memory; revocation or refresh failure sets `needs_attention` and **does not retry in a loop**. |
| HTML mail rendered in adminApp | Script/CSS injection, remote-content tracking, phishing links | Sanitise at render time with an allowlist; remote images and external CSS blocked by default; render inside a sandboxed iframe; links open only after an explicit click. |
| Unattended mail actions | A bug or future AI plugin sends, deletes or forwards on its own | The hub performs **no** server-side change (send, delete, move, forward, push) without an operator-attributed request; `queued_by`/`pushed_by` are mandatory and audited. |

### 8.2 Key management

- `HUB_SECRET_KEY` is required at startup; the hub refuses to start without it.
- Each secret row carries `key_version`; rotation = new key + re-encrypt command. Loss of the key = re-enter credentials.
- **Back up the key separately from the SQLite file.** Document it in the operator guide.

### 8.3 Regulatory notes

- FinTS access requires a **registered product ID** (DK registration). It is per *application*, not per deployment: one ID
  for ZenBon, shipped in code. The public repo makes it visible; the ecosystem treats it as an identifier, not a secret.
- Access is read-only account-information, which keeps this away from payment-initiation obligations.
- The hub handles **bank statements**, which are accounting records. pos-server's retention rules apply to what it stores;
  the hub keeps only transient copies (see above).

---

## 9. Reliability

### 9.1 Failure modes

| Failure | Behaviour |
|---|---|
| pos-server down / 5xx | Artifacts sit in the outbox; delivery retries with backoff; `/health` shows backlog and oldest age. Nothing lost. |
| Bank unreachable / dialog error | Run → `failed` with reason; next scheduled run tries again (not an auth failure, so retry is safe). |
| Bank auth failure | Connection → `needs_attention`; **no retry** (§8.1). |
| TAN unanswered | Run → `expired`; connection stays `ready`; next scheduled run issues a fresh challenge. |
| Hub crash mid-run | On boot: `running` runs → `failed(restart)`; outbox rows are durable; `awaiting_tan` resumable if the library state survived and the challenge hasn't expired. |
| Crash after bank fetch, before outbox write | Re-fetch next run; pos-server de-duplicates. Cursor advances **only after** the artifact is in the outbox. |
| Crash after outbox write, before delivery | Delivery worker resumes from `pending`. |
| SMTP outcome unknown (timeout or drop after `DATA`) | `mail_outgoing.state = unknown`; **never auto-resent** — a duplicate email to a customer or supplier is worse than a delayed one. The operator sees it and decides. |
| Send succeeded, Sent-copy append failed | State `sent`, `sent_copy_appended=0`; the append is retried (safe, it does not resend). |
| IMAP UIDVALIDITY changes | Drop that folder's cached messages and re-sync; push state of attachments is preserved by `message_id` + part. The hub never deletes mail on the server. |
| OAuth refresh fails / consent revoked | Account → `needs_attention`; no retry loop; operator re-authorises. |
| Attachment pushed twice (double click) | `outbox` is unique on `(source, sha256)`; the second call returns the existing row. |
| SQLite file lost | Re-register connections and re-enter credentials; business data unaffected. |
| Clock skew | All timestamps UTC ISO-8601; scheduling uses the monotonic clock for intervals. |

### 9.2 Why a single instance is acceptable

The hub is not in the sale path, work is minutes-latency, and every operation is idempotent downstream. Running two
instances against one SQLite file would add risk (duplicate bank dialogs, lockouts) for no benefit. Enforced with an
advisory lock on the data directory at startup — a second instance exits.

### 9.3 Observability

- Structured JSON logs with request/run correlation ids (`X-Request-ID` passed through from pos-server).
- `job_run` and `audit_event` are the operator's history; adminApp surfaces the last N runs per connection.
- `/health` returns `ok | degraded | down` and reasons: outbox backlog, oldest undelivered age, per-plugin status,
  connections in `needs_attention`, SCA validity expiring within 14 days.
- Alerting is deliberately minimal at first: pos-server polls `/health` and shows a banner. A real alert channel is a later decision.

---

## 10. Deployment

- **Artifact:** one container image (and an equivalent systemd unit): Python 3.12, `uvicorn` with **one worker**.
- **Config (env):** `POS_SERVER_URL`, `POS_INTEGRATION_KEY`, `HUB_API_KEY`, `HUB_SECRET_KEY`, `HUB_DATA_DIR`,
  `HUB_BIND` (default `127.0.0.1:8090`). Nothing else in the environment; everything operational lives in SQLite.
- **Volumes:** `HUB_DATA_DIR` holds `hub.db` (+WAL), `outbox/`, and the lock file.
- **Placement:** beside pos-server on the same host, or on a separate VPS; traffic over the existing WireGuard pattern, never the public internet.
- **Backup:** SQLite `.backup` snapshot (consistent under WAL). Not coupled to pos-server's backup initially.
- **Upgrade:** stop → replace → start; migrations run on boot; refuse to start if the DB is newer than the code.
- **Release channel:** versioned separately from pos-server. The hub does not ship inside the pos-server zip until its release story is decided.

---

## 11. Plugin deep dives

### 11.1 `fints`

- Library: `python-fints` (version **pinned**; the earlier Mac session mentioned "5.0", unverified — see §14).
- **Read-only:** account listing, statement/transaction fetch, balance. Nothing else exposed.
- **Sync algorithm per account:**
  1. window = `[last_booking_date − overlap, today]`, overlap default 7 days.
  2. Fetch in the account's preferred format (CAMT via HKCAZ if the bank supports it, else MT940).
  3. `Outbox.submit` the raw bytes with `source=fints`, `metadata={account: masked IBAN, window}`.
  4. Advance `last_booking_date` **only after** step 3 commits.
- **Raw bytes:** the hub forwards what the bank sent (assumption A2). If the library offers only parsed objects for a
  format, that format is not used until a lossless path exists.
- **Dialog hygiene:** one open dialog per connection; always end it (even on error); respect bank rate limits; no
  concurrent syncs on one connection (per-connection lock).
- **SCA tracking:** `sca_valid_until` is a hint to warn operators two weeks ahead; the bank remains the authority.

### 11.2 `mail`

An **operator-driven mail client backend**, not an intake pipeline. The operator reads supplier mail in adminApp,
replies, and decides per attachment whether it goes to pos-server. The hub never decides that for them.

**Principles**

- **The mailbox stays the source of truth.** The hub's cache is rebuildable (§6.3). Server-side changes (mark read, and
  later move/delete) happen only from an explicit, operator-attributed request. v1 writes the `\Seen` flag on open and nothing else.
- **No automation toward pos-server.** There is no poller that imports, files or forwards. The only transfer is the
  manual push.
- **MIME parsing is allowed; invoice parsing is not.** The hub parses MIME structure (stdlib `email`) to show and store mail.
  It never interprets an attachment — pos-server's `:einvoice` does that after the push.

**Sync**

- Per account and enabled folder, incremental by UID (CONDSTORE/QRESYNC when the server offers it), limited to
  `sync_since` (default 90 days). Cached: headers, text/HTML body, attachment metadata. Attachment bytes: on demand.
- Poll interval per account (default 5 min) plus an operator "refresh". IDLE is an optimisation, not a v1 requirement.
- Search: SQLite FTS5 over subject, sender and text body.

**Send**

- SMTP submission (587 STARTTLS or 465 TLS). Plain text, optional HTML. Attachments come from the operator, or later from
  pos-server documents (e.g. "send this invoice to the customer" — a separate design).
- After a successful send, the hub `APPEND`s a copy to the account's Sent folder so the real mailbox stays complete.
- Delivery is **at-most-once** for mail: ambiguous failures go to `unknown`, never to automatic retry (§9.1).

**Manual attachment push**

1. adminApp: the operator opens a message and clicks *Send to Belege* on one attachment.
2. pos-server proxy checks the capability and forwards `POST /v1/mail/attachments/{id}/push` with the operator's identity.
3. The hub fetches the bytes (cache, else IMAP), submits to the outbox with `source=mail` and message metadata, and records
   `push_state`, `pushed_by`, `pushed_at`.
4. Delivery to pos-server is the same retrying path as every other artifact; pos-server runs `:einvoice` on arrival.

**Authentication — the part that is not "just a password"**

| Account type | Mechanism | What the operator must do |
|---|---|---|
| Own server, hosting providers, most German small-business mail (IONOS, Strato, Hetzner, GMX, web.de …) | IMAP/SMTP with **password or app password**, TLS only | Enter host/port and credentials. |
| **Google** (Gmail, Workspace) | **OAuth2 / XOAUTH2** with the full-mail scope. App passwords exist only with 2-step verification and can be disabled by a Workspace admin. | Register an OAuth client in Google Cloud and enter its id/secret. The mail scope is a *restricted* scope: a publicly distributed app needs Google's verification, and an unverified app in "testing" gets short-lived refresh tokens. A self-registered client per operator avoids ZenBon becoming a verified Google app. *(Verify current Google policy before building.)* |
| **Microsoft 365 / Outlook.com** | **OAuth2 / XOAUTH2** via an Entra app registration (IMAP and SMTP delegated scopes). Basic auth is gone. | Register an app in Entra and enter its ids. A tenant admin may have to enable IMAP/SMTP AUTH for the mailbox and grant consent. A Microsoft Graph backend is the alternative if IMAP is blocked. *(Verify.)* |
| Any other OAuth-capable provider | Generic XOAUTH2 with configurable authorize/token endpoints | Same pattern, supplied by the operator. |

- **v1 uses operator-supplied OAuth client credentials**, stored encrypted. ZenBon does not ship a shared Google/Microsoft
  client until someone decides to take on the verification process (Q9).
- **Flow:** authorization-code with PKCE. adminApp opens the provider's consent page; the redirect lands on a pos-server
  route that forwards the code to the hub, which exchanges it and stores the refresh token encrypted. A device-code flow is
  the fallback for headless setups where the provider allows it.
- The hub refreshes access tokens itself. A failed refresh or revoked consent is **not retried in a loop**; the account goes
  to `needs_attention`.
- A `MailProvider` seam (IMAP/SMTP implementation first) keeps a Graph or Gmail-API implementation possible later without
  touching the cache, the send queue or the push flow.

**AI (later, separate plugin)**

- Reads the mail cache; needs an event such as `message.synced` from the core (the first real reason for an event mechanism).
- **Assistive only**: summaries, "this looks like an invoice" suggestions, reply drafts. A human confirms every action; it
  never sends, pushes or books on its own. (pos-server removed its LLM extraction in 2026-08 because plausible-but-wrong
  values were indistinguishable from correct ones — the same reasoning applies here.)
- Sending mail content to a cloud model is a data-protection decision per operator: per-account opt-in, with a local-model
  option considered before any cloud one.

---

## 12. Phasing

| Phase | Deliverable | Exit criterion |
|---|---|---|
| **0 — Spike** | Script on the workstation: log in to a real account, fetch twice, write results to a scratch SQLite. Collect the Mac session's findings. | A1–A3 answered with evidence; library version and licence confirmed; product ID registration started. |
| **1 — Core + pos-server door** | Core (registry, db, migrator, secrets, scheduler, outbox, pos_client, health) **and** the pos-server inbound endpoint with `INTEGRATION` uploader. No plugins yet; a test plugin proves the lifecycle. | Fake artifact → outbox → pos-server document, surviving a pos-server outage. |
| **2 — `fints` plugin** | Connection CRUD, scheduled + on-demand sync, TAN state machine (decoupled first). Contract frozen here (§5.2). | A real account's statement appears in pos-server, parsed by `:banking`. |
| **3 — Admin UI path** | pos-server proxy routes + capability + adminApp screens (connections, runs, TAN prompt, health banner). | Operator sets up a bank and approves a TAN without a shell. |
| **4 — `mail` plugin** | Can be split. **Order for a Gmail first mailbox:** 4c Google OAuth2 first (Gmail is the first real account, so password-only would test nothing real; an app password is only a stopgap and needs 2-step verification and an admin who has not disabled it), then 4a IMAP sync into the cache + search, 4b SMTP send with Sent-copy, 4d manual attachment push. Microsoft and generic-password accounts follow. n8n plan retired. | An operator reads a supplier mail in adminApp, replies, and pushes a ZUGFeRD attachment to Belege with one click. |
| **5 — Operator packaging** | Image/systemd unit, docs, key rotation, SCA-expiry warnings, restore drill. | A second operator installs it from the guide alone. |
| **6+ — `ai` plugin etc.** | Designed when needed. | — |

Phase 0 can start now and needs no pos-server work. Phase 1's pos-server half is the critical path for everything after it.

---

## 13. Decisions and trade-offs

| # | Decision | Alternatives rejected | Why | Revisit when |
|---|---|---|---|---|
| D1 | Separate Python service | Embed FinTS in Kotlin (hbci4j); keep n8n | Best FinTS library lives in Python; AI plugins land there too; n8n is a heavy dependency for two integrations. | Python FinTS library is abandoned, or a JVM option matches it. |
| D2 | Hub pushes to pos-server; pos-server never calls banks/mail | pos-server pulls/embeds | Keeps outages off the till path; one narrow contract. | Never expected. |
| D3 | Reuse existing document upload; Python does **no parsing** | Python parses and writes statements to the DB | `:banking`/`:einvoice` are tested and are the single parser; second parser = second truth. | Never; if a format isn't parseable, extend Kotlin. |
| D4 | SQLite for operational state only | Postgres; JSON files | One process, tiny data; zero ops. | Multi-instance hub, or state that must be queried across systems. |
| D5 | Plain-SQL namespaced migrations | Alembic; ORM auto-create | Matches repo habit; per-plugin independence is simple. | Schema complexity grows past what SQL files can express cleanly. |
| D6 | Entry-point discovery, restart to add | Hot loading | Hot-add buys nothing here and costs unload/cleanup complexity. | A plugin must be installed without any downtime. |
| D7 | First-party in-process plugins only | Sandboxed third-party plugins | Secrets are in-process; sandboxing is a separate project. | A real third-party ecosystem is wanted. |
| D8 | adminApp → pos-server → hub | adminApp → hub directly | Reuses auth, capabilities and audit; hub stays unexposed. | Never expected. |
| D9 | Read-only banking | Payment initiation | Different threat model and regulation. | Explicit product decision with its own design. |
| D10 | One outbox for all plugins | Per-plugin delivery | Retry/backoff/dedup in one place. | A plugin needs different delivery semantics (e.g. synchronous result). |
| D11 | No auto-retry on bank auth failure | Backoff retry | Wrong-PIN attempts can lock a real person out of their bank. | Never. |
| D12 | Contract frozen only after two plugins exist | Design now | First interface is always wrong. | — |
| D13 | Mail is operator-driven: sync cache, send, **manual** attachment push | Automatic invoice intake (the n8n model) | The operator wants to read and decide; automation would file things nobody looked at and put machine judgement ahead of theirs. | If a supplier's mail becomes a pure, trusted feed worth a separate, opt-in intake. |
| D14 | Mailbox on the server is the source of truth; hub keeps a rebuildable cache | Hub as the primary mail store | Keeps the hub disposable and avoids owning mail as business data. | If search/offline needs outgrow a cache. |
| D15 | OAuth2 with **operator-supplied** client credentials in v1 | ZenBon ships one verified Google/Microsoft client | Verification and security assessment is a programme of its own. | When many operators install it and per-operator registration is the friction (Q9). |
| D16 | Mail send is at-most-once; ambiguous outcome → `unknown`, operator decides | Retry until acknowledged | A duplicate email to a customer or supplier is worse than a delayed one. | Never. |

---

## 14. Open questions

| # | Question | Blocks | Owner |
|---|---|---|---|
| Q1 | What did the Mac FinTS session learn: bank, library, TAN method, product ID, second fetch without TAN? | Phase 0 conclusions | Maintainer |
| Q2 | Library version — is there a "5.0", or was that FinTS protocol 3.0 or an older/newer package? `pip show fints`. | Pinning, §11.1 | Maintainer |
| Q3 | Does pos-server still reject plain-text MT940 uploads (415)? If so, who fixes it and when? | MT940 path | pos-server |
| Q4 | Exact pos-server response for a duplicate-bytes upload (status/body) so the hub can map it to "delivered". | Phase 1 | pos-server |
| Q5 | Is the FinTS library's licence (believed LGPL-3.0) acceptable under the repo's licence policy for a separately distributed service? | Distribution | Maintainer |
| Q6 | Which operators will install this, and who supports bank-specific quirks? | Phase 5 scope | Maintainer |
| Q7 | Where does the "TAN waiting" notification surface besides the banking screen (push, email)? | Phase 3 | Maintainer |
| Q8 | Does any of the n8n design exist elsewhere (a branch)? The repo's `dev` has none of it. | Scope of "replace n8n" | Maintainer |
| Q9 | Who registers the Google/Microsoft OAuth clients — each operator (v1 default) or ZenBon once (needs Google verification)? | Phase 4c | Maintainer |
| Q10 | Mailbox access control: one capability for all mail, or per-account grants (shared info@ vs. personal inboxes)? | Phase 3/4 | Maintainer |
| Q11 | Is an unencrypted SQLite mail cache acceptable (disk encryption assumed), or is SQLCipher required? | Phase 4a | Maintainer |
| Q12 | ~~First mailbox provider~~ **Answered 2026-10-27: Gmail / Google Workspace.** Still open: personal `@gmail.com` or a Workspace domain? It decides the OAuth consent-screen type (Q13). | Phase 4c | Maintainer |
| Q13 | Google OAuth consent screen: Workspace **Internal** (no verification, no 7-day token expiry), or **External** for a personal account (Testing mode expires refresh tokens after ~7 days; "In production" unverified shows a warning screen and has a user cap)? Verify Google's current rules before building. | Phase 4c | Maintainer |

---

## 15. What I would revisit as the system grows

1. **SQLite → something else** once there is more than one hub instance or a need to query across installs.
2. **In-process plugins → isolated plugins** if anyone outside the team wants to write one.
3. **Polling → push/IDLE** for mail if latency ever matters; for FinTS there is no push, ever.
4. **The outbox** if a plugin needs a *response* from pos-server (e.g. classification result) — that is request/response, not courier delivery, and would justify an events mechanism.
5. **Alerting**: banner-on-poll is a placeholder for a real channel.
6. **Secret storage**: env-keyed encryption is adequate for a single operator's box; a managed secret store may be right for many installs.
7. **The mail cache**: if operators want offline access, long retention or cross-mailbox analytics, the cache stops being disposable and the "rebuildable" principle (D14) has to be revisited.
