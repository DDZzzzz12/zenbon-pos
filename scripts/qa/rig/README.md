# QA rig helpers

Small tools for the isolated release-QA rig. The full procedure, the reasons behind each step and
the known traps are in [`docs/testing/qa-rig.md`](../../../docs/testing/qa-rig.md); the cases they
serve are in [`docs/testing/release-qa-catalogue.md`](../../../docs/testing/release-qa-catalogue.md).

Nothing here holds a machine path or a credential. Paths come from arguments or `QA_DIR`;
credentials come from environment variables (keep them in an untracked env file inside the scratch
dir and `source` it).

| File | What it does |
|---|---|
| `printcp.gradle` | Gradle init script adding `:pos-server:printRvCp` (runVirtual classpath) and `printRunCp` (run classpath, for old tags) |
| `snapcp.sh` | Copies the worktree-local classpath entries into a snapshot dir so later builds cannot change a running server |
| `qa-server.sh` | Starts one isolated server: own embedded Postgres + data dir, scratch `user.home`, JWT dev mode off, virtual TSE, macOS write-deny sandbox |
| `qa_api.py` | Authenticated API calls from the shell (`-u <ENV_PREFIX>`, `-d <device id>`, `-o <file>`) |
| `PgQuery.java` | JDBC query tool (the embedded Postgres ships no `psql`); connection from `QA_PG_*` env vars |
| `print_listener.py` | Raw TCP listener standing in for a NETWORK receipt printer; one file per job |
| `escpos_decode.py` | Turns a captured ESC/POS job into text (code page 858, QR block removed) |
| `fake_ipp.py` | Minimal IPP printer that stores Print-Job documents (the PDF print path) |
| `adb_ui.py` | Drives the till/station emulator by exact visible text: `setup`, `dump`, `tap`, `wait`, `secret` (types an env var unseen), `shot`; dismisses host-load ANR dialogs and says so |
| `leak_scan.py` | Proves a public `build/deploy` carries no Swissbit SDK (release.yml's checks, nested jars included); used by `../gate.sh` |

The pass-level commands sit one directory up: `rig.sh` (wraps `snapcp.sh` + `qa-server.sh` with the
isolation checks), `seed_fixture.py`, `api_smoke.py`, `gate.sh`, `cases.py`. The runbook is the
`release-qa` skill in `.claude/skills/release-qa/`.

## Quick start

```bash
export QA_DIR=/private/tmp/qa-$(date +%Y%m%d)       # outside $HOME (macOS; on Linux use /tmp/qa-...)
mkdir -p "$QA_DIR"
WT=/path/to/a/clean/worktree/at/the/commit/under/test
RIG=$PWD/scripts/qa/rig                                # run from the repo root

# 1. classpath -> snapshot
(cd "$WT" && ./gradlew --no-configuration-cache -I "$RIG/printcp.gradle" \
    -Dqa.cpout="$QA_DIR/cp.raw.txt" :pos-server:printRvCp)
scripts/qa/rig/snapcp.sh "$QA_DIR/cp.raw.txt" "$QA_DIR/cpsnap" "$QA_DIR/cp.snap.txt" "$WT"

# 2. pick a free Postgres port (5432-5499) and start
for p in $(seq 5432 5499); do lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null || { echo $p; break; }; done
scripts/qa/rig/qa-server.sh pass1 5491 8080 v1 &      # log: $QA_DIR/server-pass1.log
curl -s localhost:8080/api/setup/status               # isInitialized:false + zero counts = isolated

# 3. capture printers
scripts/qa/rig/print_listener.py --port 9107 --out "$QA_DIR/prn" &
scripts/qa/rig/fake_ipp.py --port 9631 --out "$QA_DIR/ipp" &

# 4. checks
QA_USER=... QA_PASSWORD=... scripts/qa/release_checks.py --date "$(date +%F)"
python3 -m unittest discover scripts/qa/tests       # offline tests of the check rules, no server

# teardown
pkill -f "home-pass1"; pkill -f print_listener.py; pkill -f fake_ipp.py
```

SQL from the shell:

```bash
export QA_PG_PORT=5491 QA_PG_USER=... QA_PG_PASSWORD=...
export QA_PGJDBC_JAR=$(find ~/.gradle/caches -name 'postgresql-42.*.jar' | head -1)
java -cp "$QA_PGJDBC_JAR" scripts/qa/rig/PgQuery.java "select count(*) from bill where zbon_id is null"
```
