# PostgreSQL-backed migration tests

The default `:pos-server:test` suite runs against **H2** with `initDB(runMigrations = false)`, so the
real Flyway migration SQL never executes there. Two opt-in, PostgreSQL-backed layers close that gap.
Both are gated behind JUnit tags and their own Gradle tasks — the default suite stays H2-only, so a
missing PG binary or a missing backup can never turn it red.

| Layer | Task | Tag | DB | Data | Runs in CI? |
|-------|------|-----|----|------|-------------|
| (a) migration chain | `:pos-server:postgresTest` | `postgres` | zonky embedded PG | synthetic seed | **yes** |
| (b) real-data check | `:pos-server:realdataTest` | `realdata` | embedded zonky PG (Docker optional) | a real backup | **no — local only** |

---

## Layer (a) — `postgresTest` (CI)

`MigrationChainTest` starts an embedded PostgreSQL (zonky, ephemeral port), seeds the pre-migration
shape of exactly the tables the round-5/7 migrations touch, then drives Flyway **directly** so
V78..V{max} genuinely execute (not `initDB`'s fresh-baseline shortcut, which skips every migration).
Each assertion fails if its migration is reverted. Synthetic data only — no PII.

```bash
./gradlew :pos-server:postgresTest --rerun-tasks
```

The migrations on disk are **V33..V79** and are incremental `ALTER`/`UPDATE`s on an Exposed-created
base schema (no migration `CREATE`s the fiscal base tables), so a from-empty V1→V79 run is not
possible; the test materialises the touched slice and applies V78..V79 on top of a V77 baseline.

### arm64 / binary note

The zonky binaries are declared as `testRuntimeOnly` for **linux-amd64** (CI) and **darwin-amd64**.
There is **no arm64 alias** in the version catalog. On Apple Silicon the darwin binary artifact
resolves an arch-appropriate build and runs natively (zonky logs `Detected a Darwin aarch64 system`);
if you ever hit an amd64-only binary it runs under Rosetta. `linux-amd64` covers Linux CI runners.

---

## Layer (b) — `realdataTest` (local only, never CI)

`RealDataMigrationCheck` is the real-data counterpart. By default it stands up an **embedded zonky
PostgreSQL** (no Docker required) and restores a **real production backup** into the current schema;
it uses Docker PostgreSQL only when `POS_TEST_PG_URL` is set or one is already reachable. It then
replays the **fiscal consolidation V78** (formerly V94..V97) over the real rows and runs a full
**DSFinV-K export** over the real closings, asserting it completes.

> **It does NOT run a full V77→Vmax replay — and cannot.** The restore is *data only*
> (`jdbc-copy-csv`, no DDL) into the schema Exposed already built at the current version, so the
> historical rows land in an **already-current** schema. A Flyway replay from the backup's own
> version (V76) dies at the first additive migration, **V77**
> (`ALTER TABLE product_extras ADD COLUMN linked_product_id` → SQLSTATE `42701 duplicate_column`),
> because the column already exists. Production upgrades run Flyway *before* Exposed on a genuine V76
> DB, so the additive DDL applies cleanly there; only this harness pre-materialises the current
> schema. What it *can* replay is the **fiscal consolidation V78** (formerly V94..V97): it was
> written to be re-runnable (`IF NOT EXISTS` / idempotent DDL that no-ops against the current
> schema, plus DML), so baselining Flyway at V77 puts the **real** `zbon` / `shop_info` / `entry_log`
> rows through V78's Stammdaten backfill and PLZ repair. The check reports the real-data
> outcome (structural counts only): whether V78's `entry_log` precondition held and how many closings
> it backfilled, and how many rows carry the `>5`-digit PLZ V78 repairs. **Layer (a)
> (`MigrationChainTest`) remains the deterministic pass/fail proof of these migrations** on a
> synthetic V77→Vmax pre-state; this layer is the real-data complement, and the DSFinV-K export is
> the real cert-path payoff.

It reuses `BackupRestoreServiceImpl.restoreBackup(ByteArray)` verbatim (the `jdbc-copy-csv`
COPY-protocol path); the pre-restore safety-backup mechanism is left **enabled**, merely pointed at a
temp directory.

### Running it (default — no Docker)

1. **Drop a backup** into the repo-root `test-data/` directory (gitignored):

   ```
   test-data/real-backup.zip
   ```

   Or point `POS_TEST_BACKUP` at any path (absolute is safest).

2. **Run the check.** With no `POS_TEST_PG_URL` and no reachable Docker PG, it stands up an ephemeral
   embedded zonky PostgreSQL automatically and tears it down afterwards — nothing to start or stop:

   ```bash
   ./gradlew :pos-server:realdataTest --rerun-tasks
   ```

### Optional — run against a persistent Docker PostgreSQL

Use this only if you want a DB you can inspect afterwards. Set `POS_TEST_PG_URL` (or leave it unset
and have a PG already reachable at `localhost:5440`); the check then uses Docker instead of zonky.

1. **Start Docker PostgreSQL 16.13** (pinned to the backup's `pgVersion`):

   ```bash
   docker compose -f docker/docker-compose.yml up -d
   docker compose -f docker/docker-compose.yml ps   # wait for healthy
   ```

   It listens on host port **5440** (not 5432/5435, to avoid collisions).

2. **Run the check**, then **tear down** when done:

   ```bash
   ./gradlew :pos-server:realdataTest --rerun-tasks
   docker compose -f docker/docker-compose.yml down -v   # -v also drops the data volume
   ```

### Connection overrides (defaults match the compose file)

| Env | Default |
|-----|---------|
| `POS_TEST_BACKUP` | `test-data/real-backup.zip` (relative to repo root) |
| `POS_TEST_PG_URL` | `jdbc:postgresql://localhost:5440/pos_db` |
| `POS_TEST_PG_USER` | `pos` |
| `POS_TEST_PG_PASSWORD` | `pos` |

### It skips loudly — never vacuously green

If the backup is missing, or Docker is not reachable, the test **aborts with an explaining message**
(reported as *skipped*, not passed). You will see exactly which precondition was not met.

### PII rules — read before you touch a real backup

- **A real backup contains production PII.** It must **never** be committed. `test-data/` is
  gitignored (`/test-data/` in the root `.gitignore`); keep it that way.
- **CI never uses a real backup and never starts Docker.** This layer is developer-machine only.
- Row contents are **never logged** by the test; only counts, versions, and byte sizes are.
- Do not copy a real backup outside `test-data/`, and delete it when you are done.
