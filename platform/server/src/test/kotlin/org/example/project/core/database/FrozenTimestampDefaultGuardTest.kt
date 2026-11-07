package org.example.project.core.database

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Standing source-level guard for audit finding KABS-1.
 *
 * Exposed's `Column.default(value)` takes a VALUE, evaluated exactly once at table-object
 * initialisation. Writing `datetime("created_at")` with a `default(...)` of a *called* clock
 * function therefore freezes a single wall-clock instant into both the Kotlin default and the
 * emitted DDL default, so every row inserted without an explicit value shares one fabricated
 * creation time. On `kassenbuch_header` this silently dropped entries from their DSFinV-K closing
 * and fabricated every BON_START/BON_ENDE.
 *
 * The correct form is `clientDefault { ... }` — the lambda runs per insert, and it nulls
 * `dbDefaultValue` so Exposed always writes the column explicitly.
 *
 * Deliberately NOT satisfied by `defaultExpression(CurrentDateTime)`: that evaluates in the DB
 * server's timezone and bypasses the shop-configured timeProvider, the fiscal clock used everywhere
 * else in this codebase.
 *
 * ## Round-5 hardening
 *
 * **Scope** was `repoRoot().walkTopDown()`, which descends into `.claude/worktrees` — sibling
 * checkouts pinned at foreign commits, whose offenders are unfixable from the developer's tree.
 * In the team's primary worktree that scan reached 4903 files and 80 hits, 66 of them inside
 * `.claude/`. The `.git` exclusion never fired for them because a nested worktree's `.git` is a
 * FILE, not a directory. An unfixably-red guard gets `@Disabled`, and then the 16-site sweep this
 * protects has nothing. Scope is now derived from the modules declared in `settings.gradle.kts`,
 * so only real module sources are ever scanned.
 *
 * **Matcher** was the single literal `.default(` + `currentTimeStamp())`, matched per line with
 * `contains`. That missed `.default(currentDate())` and `.default(currentInstant())` — both compile
 * against real column types here (`currentDate(): LocalDate` matches `Table.date()`, of which six
 * exist; `currentInstant(): Instant` matches `Table.timestamp()`) and are the same bug — plus any
 * whitespace or line-break variant. It also matched inside comments: the KDoc on
 * `KassenbuchHeaderTable.createdAt` sits one character (a backtick) away from the old literal, and
 * `EntryLogTable` already spells the dotted `Column.default(value)` in prose. A doc edit must never
 * fail CI. The matcher is now a regex over comment-stripped, string-literal-blanked file text.
 */
@DisplayName("No Exposed table may freeze a clock call into .default(...) — KABS-1")
class FrozenTimestampDefaultGuardTest {
    /**
     * The forbidden shape: `.default(` applied to a CALL of a clock function. Constant defaults
     * such as `.default(BigDecimal.valueOf(1.00))` or `.default(false)` are legitimate and must not
     * match — only a clock read frozen at table-init is the bug.
     *
     * Assembled from parts so this file is not its own offender under a naive grep.
     *
     * ## Round-7 Task 10a — the leaf set is now the clock reads AND the primitives they wrap
     *
     * The matcher previously recognised only the three project helpers
     * `currentTimeStamp`/`currentDate`/`currentInstant`. Those are the complete set of
     * column-usable clock reads the codebase *idiomatically* exposes — `server-common`'s
     * `TimeUtils.kt` defines exactly `currentTimeStamp(): LocalDateTime`,
     * `currentInstant(): Instant` and `currentDate(): LocalDate`, one per Exposed column type — but
     * a developer who bypasses the helper and inlines what it wraps is the "4th spelling" a
     * name-only matcher silently misses. So the leaf set is broadened to the underlying primitives:
     * `Clock.System.now()` (what `currentInstant` calls), `System.currentTimeMillis()` /
     * `System.nanoTime()` (a frozen epoch in a `long()` column), and any receiver's `.now()`
     * (`Instant.now()`, `LocalDateTime.now()`, `timeProvider.now()`, `TimezoneConfig.now()` …). An
     * optional dotted qualifier before the leaf lets `Clock.System.now`, `System.currentTimeMillis`
     * etc. match while a constant factory call like `.default(BigDecimal.valueOf(1.00))` still does
     * not (its leaf `valueOf` is not a clock read). `defaultExpression(CurrentDateTime)` — the
     * legitimate DB-side form — is untouched: the pattern is `.default(`, and `.defaultExpression(`
     * has no `.default(` substring.
     */
    private val forbidden =
        Regex(
            "\\.default\\(\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\.)*(" +
                "currentTimeStamp|currentDate|currentInstant|currentTimeMillis|nanoTime|now" +
                ")\\s*\\(",
        )

    /**
     * Repo root, found by walking up from the test working directory (Gradle defaults it to the
     * module dir) until `settings.gradle.kts` appears. Resolving this way rather than via a fixed
     * `../` chain keeps the guard working if the module layout or working directory changes.
     */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("could not locate repo root (no settings.gradle.kts found walking up from ${File(".").absolutePath})")
    }

    /**
     * Gradle module directories, read from `settings.gradle.kts`. This is what confines the scan to
     * code that actually builds: anything outside a declared module — `.claude/worktrees`, vendored
     * copies, scratch checkouts — is not this repo's source and cannot be fixed from here.
     *
     * A declared module whose directory is missing fails the test rather than silently shrinking
     * the scan.
     */
    private fun moduleDirs(): List<File> {
        val root = repoRoot()
        val declared =
            Regex("""^\s*include\("(:[^"]+)"\)""", RegexOption.MULTILINE)
                .findAll(File(root, "settings.gradle.kts").readText())
                .map { it.groupValues[1] }
                .toList()

        assertTrue(declared.isNotEmpty(), "no include(\":module\") lines parsed from settings.gradle.kts")

        // Modules moved under an area folder map their Gradle path to a directory explicitly:
        // project(":platform-money").projectDir = file("platform/money").
        val mappedDirs =
            Regex("""project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
                .findAll(File(root, "settings.gradle.kts").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }

        val missing = mutableListOf<String>()
        val dirs =
            declared.mapNotNull { path ->
                val dir = File(root, mappedDirs[path] ?: path.removePrefix(":").replace(':', File.separatorChar))
                if (dir.isDirectory) {
                    dir
                } else {
                    missing.add(path)
                    null
                }
            }
        assertTrue(
            missing.isEmpty(),
            "settings.gradle.kts declares modules whose directories are missing: $missing — " +
                "the scan scope is wrong, not the sources",
        )
        return dirs
    }

    /**
     * Production Kotlin sources: every `src/main/kotlin` (JVM modules) and `src/<target>Main/kotlin`
     * (KMP modules) file under a declared module. The KMP source sets were outside the old
     * `src/main/kotlin`-only filter; including them roughly doubles real coverage.
     */
    private fun productionSources(): List<File> =
        moduleDirs().flatMap { module ->
            File(module, "src")
                .walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val rel = file.relativeTo(File(module, "src")).path.split(File.separatorChar)
                    rel.size > 2 &&
                        (rel[0] == "main" || rel[0].endsWith("Main")) &&
                        rel[1] == "kotlin"
                }.toList()
        }

    /**
     * Blanks comments, string literals and char literals — preserving newlines so reported line
     * numbers stay true — so the matcher can only ever see code. Prose describing the bug (which
     * every guarded table's KDoc necessarily contains) must not be able to fail the build.
     *
     * Kotlin block comments nest, so the depth is tracked rather than scanning for the first `*​/`.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)

        fun blank(c: Char) = out.append(if (c == '\n') '\n' else ' ')
        var i = 0
        var blockDepth = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                blockDepth > 0 ->
                    when {
                        c == '/' && next == '*' -> {
                            blockDepth++
                            out.append("  ")
                            i += 2
                        }
                        c == '*' && next == '/' -> {
                            blockDepth--
                            out.append("  ")
                            i += 2
                        }
                        else -> {
                            blank(c)
                            i++
                        }
                    }
                c == '/' && next == '*' -> {
                    blockDepth = 1
                    out.append("  ")
                    i += 2
                }
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') {
                    out.append(' ')
                    i++
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) text.length else end + 3
                    while (i < stop) {
                        blank(text[i])
                        i++
                    }
                }
                c == '"' || c == '\'' -> {
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != c) {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        if (i < text.length) {
                            blank(text[i])
                            i++
                        }
                    }
                    if (i < text.length) {
                        out.append(' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The matcher is the guard. Pinning it here means a future edit that narrows it — or that
     * accidentally starts matching prose — fails immediately, instead of quietly making the
     * repo-wide scan below vacuous.
     */
    @Test
    fun `matcher catches every frozen clock form and no legitimate default`() {
        val shouldMatch =
            listOf(
                """val a = datetime("created_at").default(currentTimeStamp())""",
                """val b = date("beleg_datum").default(currentDate())""",
                """val c = timestamp("signed_at").default(currentInstant())""",
                """val d = datetime("created_at").default( currentTimeStamp() )""",
                "val e = datetime(\"created_at\")\n    .default(\n        currentTimeStamp()\n    )",
                // Round-7 Task 10a — the primitives the three helpers wrap, inlined at the default.
                """val k = timestamp("signed_at").default(Clock.System.now())""",
                """val l = long("epoch_ms").default(System.currentTimeMillis())""",
                """val m = timestamp("t").default(Instant.now())""",
                """val n = datetime("t").default(timeProvider.now())""",
            )
        for (sample in shouldMatch) {
            assertTrue(
                forbidden.containsMatchIn(codeOnly(sample)),
                "matcher missed a frozen-clock form: $sample",
            )
        }

        val shouldNotMatch =
            listOf(
                // Genuine constant defaults — OrderItemTable.discount and friends.
                """val f = decimal("discount", precision = 4, scale = 2).default(BigDecimal.valueOf(1.00))""",
                """val g = decimal("final_price", precision = 19, scale = 4).default(BigDecimal.ZERO)""",
                """val h = bool("is_locked").default(false)""",
                // The correct form.
                """val i = datetime("created_at").clientDefault { currentTimeStamp() }""",
                // The legitimate DB-side form — `.defaultExpression(`, which has no `.default(` substring.
                """val q = datetime("created_at").defaultExpression(CurrentDateTime)""",
                // A constant factory that is still a call — leaf `valueOf` is not a clock read.
                """val r = integer("code").default(Code.forName("A").value())""",
                // Prose — the exact phrasings live in KassenbuchHeaderTable and EntryLogTable KDocs.
                "/** MUST be `clientDefault { }`, never `default(currentTimeStamp())`: `Column.default(value)` */",
                "// never .default(currentTimeStamp()) — see KABS-1",
                "val j = \"documented as .default(currentTimeStamp()) in the manual\"",
            )
        for (sample in shouldNotMatch) {
            assertEquals(
                false,
                forbidden.containsMatchIn(codeOnly(sample)),
                "matcher false-positived on a legitimate line: $sample",
            )
        }
    }

    @Test
    fun `no Exposed table uses a frozen clock default`() {
        val sources = productionSources()

        // A scan that reaches zero files passes vacuously and guards nothing. This assertion is a
        // PERMANENT part of the test, not scaffolding: it fails loudly if the roots ever stop
        // resolving. 1200 is a deliberately conservative floor — the module-derived scan currently
        // reaches ~1900 production sources.
        assertTrue(
            sources.size >= 1200,
            "guard scanned only ${sources.size} production sources — the module roots stopped " +
                "resolving, so this test was about to pass vacuously",
        )

        // Sanity-anchor: the file that carried the original finding must be in scope, EXACTLY once.
        // (The old repo-wide walk matched six copies of this name across nested worktrees and still
        // considered the anchor satisfied.)
        val anchors = sources.filter { it.name == "KassenbuchHeaderTable.kt" }
        assertEquals(
            1,
            anchors.size,
            "expected exactly one KassenbuchHeaderTable.kt in scope, found ${anchors.size}: " +
                anchors.joinToString { it.path },
        )

        val root = repoRoot()
        val offenders =
            sources.flatMap { file ->
                val code = codeOnly(file.readText())
                forbidden.findAll(code).map { match ->
                    val line = code.take(match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(root).path}:$line"
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Exposed's .default(value) evaluates ONCE at table-object init, freezing one wall-clock " +
                "instant into every row. Use .clientDefault { ... }. Offenders:\n" +
                offenders.joinToString("\n"),
        )
    }
}
