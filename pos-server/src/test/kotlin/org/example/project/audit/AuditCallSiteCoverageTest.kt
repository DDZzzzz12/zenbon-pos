package org.example.project.audit

import org.example.project.testutils.SourceRoots
import org.example.project.utils.errorHandling.ExceptionConverter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything that must stay in step with `criticalOperations`, checked against one reading of it.
 *
 * Three guarantees: an audited call site names the entity its operation acted on; a call site's
 * operation is one the whitelist actually recognises; and the manual's Prüfprotokoll table lists
 * exactly what the whitelist audits.
 *
 * The first two compound — an unrecognised operation writes no row at all, so the naming guard is
 * structurally silent about it. `"CREATE-VOUCHER"` went unaudited for the life of the feature behind
 * exactly that silence: `shouldAuditOperation` uppercases but does not normalise separators, so the
 * hyphen never matched `CREATE_VOUCHER` and no row was ever written, on success or on failure. The
 * third is the same defect one layer out — the manual described an audit trail the code does not
 * write, and 36 of the ~40 operations it named existed nowhere in Kotlin source.
 *
 * All three share [auditedOperations], and that is the reason they share a class rather than
 * following the repo's usual one-guard-per-file shape. A second parser of the same declaration is a
 * second thing to re-point when `ExceptionConverter` changes shape, and two parsers that disagree
 * about what is audited would leave both guards green while each checked a different set.
 *
 * This is the only guard that fails because PRODUCTION source or the shipped manual changed. Its sibling
 * [AuditEntityIdentityTest] drives [ExceptionConverter.catchingThrows] with mirrors of call-site
 * return shapes, so it proves the MECHANISM and is blind to the lambdas themselves — delete one and
 * every test there still passes. There is no service-level fixture for bills, cash-book entries or
 * table sessions in `pos-server/src/test` (`TestData` offers employees, terminals, shop info and
 * Z-Bons only), so an end-to-end test would be a fixture project of its own. This reads the source
 * instead, which is where the regression shows: an audited call site that passes no `auditEntity`
 * writes `entityId = "UNKNOWN"`, and 97 of 100 rows sampled on a restored production database said
 * exactly that.
 *
 * Deliberately a class of its own rather than another method on [AuditEntityIdentityTest]: these
 * tests touch no database, and that class mints a fresh in-memory one per test in `@BeforeEach`. The
 * repo already keeps its source-scanning guards apart this way — `NoRoleGatesInRoutesTest`,
 * `NoDirectEmployeeCreationTest`, `NoRoleScopingInServicesTest`, `StaleRoadmapReferenceTest`.
 *
 * Scanning source text rather than reflecting is the same choice `NoRoleGatesInRoutesTest` made and
 * for the same reason: the failure is "someone wrote a call without this argument", which no
 * reflective oracle can see.
 */
@DisplayName("Audit whitelist coverage")
class AuditCallSiteCoverageTest {
    /**
     * Audited call sites that deliberately name nothing, with the reason they cannot.
     *
     * A fabricated id is worse than `UNKNOWN`: a blank gap is visibly absent, a wrong id silently
     * points at another record. Shrink this list by making the block return an identifier, or by
     * returning null from the lambda where the block genuinely has none — never by reaching for
     * whatever number happens to be in scope.
     */
    private val knownUnnamedSites =
        setOf(
            // catchingThrows<Unit>: the block returns Unit. The only identifier in scope is the
            // tableId parameter, and that is a TABLE id under an entityType of TABLE_SESSION — it
            // would collide numerically with an unrelated real session.
            "ecr/server/src/main/kotlin/org/example/project/features/table/data/service/RTableServiceImpl.kt: DELETE_TABLE_SESSION",
            // NOT a design decision — unfinished work, and this guard is how it was found. The sweep
            // that added the lambdas enumerated `pos-server/src/main` only, and these six sit in
            // `:backoffice-timetracking`. They are ordinary audited sites and still write UNKNOWN; each
            // needs its own lambda, read off what its own block returns, exactly like the rest.
            "$TIME_TRACKING: CLOCK_IN",
            "$TIME_TRACKING: CLOCK_OUT",
            "$TIME_TRACKING: START_BREAK",
            "$TIME_TRACKING: END_BREAK",
            "$TIME_TRACKING: CREATE_MANUAL_TIME_ENTRY",
            "$TIME_TRACKING: DELETE_TIME_ENTRY",
        )

    /**
     * Call sites whose operation literal differs from a whitelisted one only by separator.
     *
     * `shouldAuditOperation` compares the literal against its set, so a hyphen where the set says
     * underscore means the operation is not audited AT ALL — strictly worse than the `UNKNOWN` this
     * whole task exists to remove, because there is no row to find.
     *
     * Empty since 2026-09-18, when `VoucherServiceImpl`'s `"CREATE-VOUCHER"` — the one entry this
     * set was created to track — was corrected to `CREATE_VOUCHER` and given its own `auditEntity`.
     * Kept as its own category rather than folded into [knownUnauditedOperations]: a separator slip
     * is a typo with a tailored remedy, not a decision that an operation is non-fiscal, and it is
     * invisible in review precisely because it reads correctly. An addition here should be rare and
     * short-lived.
     */
    private val knownSeparatorMismatches = emptySet<String>()

    /**
     * Operations deliberately left out of `criticalOperations`, with the reason they are not fiscal.
     *
     * [knownSeparatorMismatches] catches only a literal that LOOKS like a whitelisted one. An
     * operation resembling nothing in the set falls through that check and, until 2026-09-18, was
     * asserted nowhere — which is how `CORRECT_CASH_BOOK_ENTRY` came to sit unaudited beside four
     * whitelisted cash-book siblings, with no separator to give it away. That is the gap this set
     * closes: the guard now judges EVERY operation literal, not just the ones spelled suspiciously.
     *
     * Every whitelisted operation is fiscal, so an entry here asserts the opposite about its own.
     * Make that claim deliberately — the alternative is adding the operation to
     * `criticalOperations`, which starts writing rows a Kassennachschau can ask for.
     */
    private val knownUnauditedOperations =
        setOf(
            // Filing, not a fiscal event: storing or removing a scan moves no money and consumes no
            // sequence. The cash-book operations that LINK a document to a fiscal entry —
            // LINK/UNLINK/REPLACE_CASH_BOOK_DOCUMENT — are the fiscal half, and all three are
            // audited.
            "$DOCUMENTS: UPLOAD_DOCUMENT",
            "$DOCUMENTS: DELETE_DOCUMENT",
        )

    @Test
    @DisplayName("every audited catchingThrows call site names its entity")
    fun everyAuditedCallSiteNamesItsEntity() {
        val audited = auditedOperations()
        val sites = mutableListOf<String>()
        val unnamed = mutableSetOf<String>()

        forEachCallSite { where, arguments ->
            val operation = OPERATION.find(arguments)?.groupValues?.get(1) ?: return@forEachCallSite
            if (operation in audited) {
                sites += "$where: $operation"
                if (!ARGUMENT.containsMatchIn(arguments)) unnamed += "$where: $operation"
            }
        }

        // 18 is the count on 2026-09-18, not a ceiling: a new audited call site only pushes this up,
        // and the assertion below judges those. A FALL means a source root stopped being scanned —
        // a fully-NAMED module dropping out would leave `unnamed` intact and satisfy every other
        // check here, so this floor is the only thing that would notice.
        assertTrue(
            sites.size >= 18,
            "found only ${sites.size} audited call sites — a source root stopped being scanned: $sites",
        )
        assertEquals(
            knownUnnamedSites,
            unnamed,
            "an audited operation must name its entity, or be listed in knownUnnamedSites with its reason",
        )
    }

    /**
     * The companion guard: an operation that is not audited at all writes NO row, named or not.
     *
     * [everyAuditedCallSiteNamesItsEntity] can only judge sites whose operation is in the whitelist,
     * so it is silent about the failure that produces no audit row in the first place. Both halves
     * are needed: that one stops a row from being anonymous, this one stops it from being absent.
     */
    @Test
    @DisplayName("every catchingThrows operation literal is audited or allowlisted")
    fun everyOperationLiteralIsAuditedOrAllowlisted() {
        val audited = auditedOperations()
        val separatorMismatches = mutableSetOf<String>()
        val unaudited = mutableSetOf<String>()

        forEachCallSite { where, arguments ->
            val operation = OPERATION.find(arguments)?.groupValues?.get(1) ?: return@forEachCallSite
            when {
                operation in audited -> Unit
                operation.replace('-', '_').uppercase() in audited -> separatorMismatches += "$where: $operation"
                else -> unaudited += "$where: $operation"
            }
        }

        // Asserted before the broader set: both describe the same defect, but this one names the
        // remedy exactly, and reporting a hyphen as "decide whether this is fiscal" would send a
        // reader looking for a decision where there is only a typo.
        assertEquals(
            knownSeparatorMismatches,
            separatorMismatches,
            "an operation literal differing from a whitelisted one only by separator is NOT audited at all — " +
                "shouldAuditOperation matches the literal, so no audit row is written. Fix the spelling, or " +
                "list it in knownSeparatorMismatches as a tracked defect. Audited spellings: ${audited.sorted()}",
        )
        assertEquals(
            knownUnauditedOperations,
            unaudited,
            "a catchingThrows operation outside criticalOperations writes no transaction_audit_log row at all. " +
                "Add it to criticalOperations if the operation is fiscal, or list it in knownUnauditedOperations " +
                "with the reason it is not. Audited spellings: ${audited.sorted()}",
        )
    }

    /**
     * The third thing that must stay in step with the whitelist: what the manual tells an auditor.
     *
     * `kap-15-pruefprotokoll.html` is the Prüfprotokoll chapter — the page someone reaches for
     * during a Kassennachschau to learn which operations leave a trail. On 2026-09-18 its table
     * named ~40 operations of which **36 existed nowhere in Kotlin source** (`SPLIT_BILL`,
     * `PROCESS_PAYMENT`, `OPEN_CASH_REGISTER`, `CREATE_KASSENBUCH_ENTRY`, …), while the genuinely
     * audited voucher and time-tracking operations were missing from it entirely. Only 5 of the
     * listed names overlapped `criticalOperations`.
     *
     * Both directions of that failure mislead, which is why this asserts set EQUALITY rather than
     * containment. A name in the manual that the code does not audit sends a reader hunting for
     * rows that were never written, and they reasonably conclude the trail has gaps. A name the
     * code audits but the manual omits hides a record from someone entitled to ask for it.
     *
     * Reads the same [auditedOperations] the other two guards do, deliberately: a second parser of
     * one declaration is a second thing to re-point when `ExceptionConverter` changes shape, and
     * the two would then disagree about what is audited with nothing to notice.
     */
    @Test
    @DisplayName("the manual's audited-operations table matches criticalOperations")
    fun theManualTableMatchesTheWhitelist() {
        val audited = auditedOperations()
        val documented = documentedOperations()

        // Named rather than left to the set diff: assertEquals prints both sets in full, and finding
        // which two of twenty-five names moved is the reader's job then. Each side gets the sentence
        // that says why ITS direction misleads, because the fix differs — one edits the table, the
        // other may mean an operation should never have been audited.
        assertEquals(
            audited,
            documented,
            "the Prüfprotokoll chapter's operations table has drifted from criticalOperations.\n" +
                "  audited but NOT listed — hides a record from someone entitled to ask for it: " +
                "${(audited - documented).sorted()}\n" +
                "  listed but NOT audited — sends an auditor hunting for rows that were never " +
                "written: ${(documented - audited).sorted()}\n" +
                "Update the table under id=\"$TABLE_ANCHOR\" in $MANUAL_PAGE",
        )
    }

    /**
     * The operation names in the manual table, read from the page rather than restated here.
     *
     * Narrowed to the `<tbody>` that follows [TABLE_ANCHOR] before anything is read, because the
     * same page carries an Autorisierungstypen table whose cells hold `DIRECT` and
     * `TEMPORARY_SESSION` — indistinguishable from operation names by shape, and silently merged
     * into the set by any page-wide scan.
     *
     * Only the SECOND cell of each row is read. The first is a German Bereich label, which happens
     * not to collide today, but "TSE" or "DSFinV-K" as a future Bereich would parse as an
     * operation. And each comma-separated token must match [OPERATION_CHARS] whole, so prose
     * appended to a cell — "CREATE_BILL, und weitere" — fails loudly here instead of being quietly
     * dropped and leaving the set looking correct.
     */
    private fun documentedOperations(): Set<String> {
        val page = File(MANUAL_PAGE)
        assertTrue(page.isFile, "not found at ${page.absolutePath} — this guard would be scanning nothing")
        val html = page.readText()

        // The id ATTRIBUTE, not the bare anchor text. The sidebar links only whole chapters today, so
        // the bare string happens to be unique — but the day one links `#kap-15-operationen`, that
        // nav entry sits earlier in the file than the heading and this would narrow to whichever
        // table came first, silently comparing against the wrong one.
        val heading = html.indexOf("id=\"$TABLE_ANCHOR\"")
        assertTrue(heading > 0, "id=\"$TABLE_ANCHOR\" is gone from ${page.name} — re-point this guard")
        val bodyStart = html.indexOf("<tbody>", heading)
        val bodyEnd = html.indexOf("</tbody>", bodyStart.coerceAtLeast(0))
        assertTrue(bodyStart > heading, "no <tbody> follows id=\"$TABLE_ANCHOR\" — re-point this guard")
        assertTrue(bodyEnd > bodyStart, "the operations table's <tbody> is unterminated — re-point this guard")

        val rows = ROW.findAll(html.substring(bodyStart, bodyEnd)).toList()
        // Guard the guard: a narrowing that silently matches nothing would turn the assertion above
        // into "expected 25 operations, found none", which reads as a gutted manual rather than as a
        // broken parse. 9 is the Bereich count on 2026-09-18 — a floor, not a ceiling.
        assertTrue(rows.size >= 9, "parsed ${rows.size} rows out of the operations table — the parse broke, not the page")

        return rows
            .flatMap { row ->
                val cells = CELL.findAll(row.groupValues[1]).map { TAG.replace(it.groupValues[1], "").trim() }.toList()
                assertTrue(cells.size >= 2, "a row of the operations table has ${cells.size} cells, expected Bereich + Operationen")
                cells[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }.onEach { token ->
                assertTrue(
                    token.matches(OPERATION_NAME),
                    "\"$token\" sits in the manual's Operationen column but is not an operation name. " +
                        "That column is a comma-separated list of names and nothing else — prose there is " +
                        "invisible to every reader who greps it.",
                )
            }.toSet()
    }

    /**
     * Visits every `ExceptionConverter.catchingThrows` call under [serverSourceRoots], handing the
     * visitor its repo-relative location and its TOP-LEVEL arguments.
     *
     * Two separate narrowings, and the guard was wrong without either. Comments are stripped from
     * the whole file BEFORE anything is located, so a KDoc that merely mentions
     * `ExceptionConverter.catchingThrows(...)` — [org.example.project.utils.log.AuditEntityRef]'s
     * does, inside a scanned root — is not mistaken for a call site, and a maintainer cannot answer
     * the guard with `// nothing to name here, so no auditEntity`. And only arguments at paren depth
     * 1 outside any braces are handed over, because a `block = { … }` body runs to 17,730 characters
     * in `BillServiceImpl` and any `auditEntity =` anywhere inside it — a nested call, a log line —
     * would otherwise satisfy the check for the OUTER call.
     */
    private fun forEachCallSite(visit: (where: String, arguments: String) -> Unit) {
        val repoRoot = SourceRoots.repoRoot.canonicalFile
        val files = SourceRoots.kotlinFiles(serverSourceRoots())
        SourceRoots.requireScanned("AuditCallSiteCoverageTest", files.size, MINIMUM_SCANNED_FILES)
        files.forEach { file ->
            val code = stripComments(file.readText())
            val where = file.canonicalFile.relativeTo(repoRoot).path
            var index = code.indexOf(CALL)
            while (index >= 0) {
                visit(where, topLevelArguments(code, code.indexOf('(', index)))
                index = code.indexOf(CALL, index + CALL.length)
            }
        }
    }

    /**
     * Source roots scanned: every module that has a `src/main/kotlin` ([SourceRoots.allModuleMainRoots]).
     *
     * Deliberately broader than where the offenders sit today — `catchingThrows` lives in
     * `:platform-data` and any server module may call it, and a guard that looks only where the problem
     * was found asks a narrower question than its name claims. That breadth is what turned up the
     * six `:backoffice-timetracking` sites the original enumeration missed. The discovery is
     * de-duplicated by canonical path, so no file is read twice and the sanity floor never counts a
     * coverage it does not have.
     */
    private fun serverSourceRoots(): List<File> = SourceRoots.allModuleMainRoots()

    /**
     * The operations [ExceptionConverter] actually audits, read from its own declaration rather than
     * copied, so an operation added to the whitelist is covered the moment it is added.
     */
    private fun auditedOperations(): Set<String> {
        val source =
            SourceRoots.fileUnder(SourceRoots.platformDataMainRoots(), "org/example/project/utils/errorHandling/ExceptionConverter.kt")
        val code = stripComments(source.readText())
        val declaration = code.indexOf("criticalOperations")
        assertTrue(declaration > 0, "shouldAuditOperation no longer declares `criticalOperations` — re-point this guard")
        val literal = topLevelArguments(code, code.indexOf('(', code.indexOf("setOf", declaration)))
        // The SAME character class the call sites are read with. A narrower one here would drop an
        // audited operation carrying a digit out of this set, and every call site naming it would
        // then go unchecked — the guard passing because it stopped looking.
        val operations = Regex("\"($OPERATION_CHARS)\"").findAll(literal).map { it.groupValues[1] }.toSet()
        assertTrue(
            "CREATE_BILL" in operations && operations.size >= 20,
            "parsed ${operations.size} audited operations — the parse broke, not the code",
        )
        return operations
    }

    /**
     * [source] with every comment removed and every literal preserved.
     *
     * Done once per file and before anything is located, so neither the search for a call nor the
     * paren balancing can be misled by text a maintainer wrote in prose.
     */
    private fun stripComments(source: String): String {
        val code = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                // One space, not nothing: a block comment can sit mid-expression, and dropping it
                // outright would weld the tokens on either side into one.
                source.startsWith("/*", i) -> {
                    code.append(' ')
                    i = source.indexOf("*/", i).takeIf { it >= 0 }?.plus(2) ?: source.length
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val end = endOfLiteral(source, i)
                    code.append(source, i, end)
                    i = end
                }
                else -> {
                    code.append(source[i])
                    i++
                }
            }
        }
        return code.toString()
    }

    /**
     * The arguments of the call whose opening parenthesis sits at [open] in already-stripped [code],
     * keeping only what is at paren depth 1 and outside every brace.
     *
     * Lambda bodies therefore elide to nothing while `auditEntity =` and `operation = "…"` survive,
     * which is precisely the scoping a plain substring search lacks. Literals are copied verbatim
     * and never counted, so the parenthesis in `context = "createBill(employeeId=$id)"` and the
     * brace in a char literal cannot skew the depth.
     */
    private fun topLevelArguments(
        code: String,
        open: Int,
    ): String {
        require(open >= 0 && code[open] == '(') { "expected an opening parenthesis at $open" }
        val kept = StringBuilder()
        var parens = 0
        var braces = 0
        var i = open
        while (i < code.length) {
            val char = code[i]
            if (char == '"' || char == '\'') {
                val end = endOfLiteral(code, i)
                if (parens == 1 && braces == 0) kept.append(code, i, end)
                i = end
                continue
            }
            when (char) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) return kept.toString()
                }
                '{' -> braces++
                '}' -> braces--
                else -> if (parens == 1 && braces == 0) kept.append(char)
            }
            i++
        }
        error("unbalanced argument list starting at $open")
    }

    /**
     * The index just past the literal starting at [start] — a raw string, a plain string, or a CHAR.
     *
     * Char literals are not an afterthought: `'"'` would otherwise open a string that runs to the
     * next quote in the file, and `'('` / `')'` would skew the depth. Both failures surfaced as
     * `error("unbalanced argument list …")`, which is loud and therefore safe, but blames production
     * source for a gap in this scanner.
     */
    private fun endOfLiteral(
        code: String,
        start: Int,
    ): Int {
        if (code.startsWith("\"\"\"", start)) {
            return code.indexOf("\"\"\"", start + 3).takeIf { it >= 0 }?.plus(3) ?: code.length
        }
        val quote = code[start]
        var i = start + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i++
                quote -> return i + 1
            }
            i++
        }
        return code.length
    }

    private companion object {
        const val CALL = "ExceptionConverter.catchingThrows"

        /**
         * Hyphens and digits are matched so the scan READS what the source says rather than skipping
         * a literal it cannot parse. That is how `VoucherServiceImpl`'s `"CREATE-VOUCHER"` becomes
         * assertable instead of invisible; a narrower class would have silently agreed with the bug.
         */
        const val OPERATION_CHARS = "[A-Za-z0-9_-]+"

        val OPERATION = Regex("""operation\s*=\s*"($OPERATION_CHARS)"""")

        /** An ARGUMENT, not the bare word — a comment mentioning it is already gone by this point. */
        val ARGUMENT = Regex("""auditEntity\s*=""")

        /** Kotlin files under [serverSourceRoots] on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 1488

        const val MANUAL_PAGE = "src/main/resources/manual/kap-15-pruefprotokoll.html"

        /** The heading anchor, not the table tag: the page holds several tables and one of this id. */
        const val TABLE_ANCHOR = "kap-15-operationen"

        val ROW = Regex("""<tr>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        val CELL = Regex("""<td>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("""<[^>]+>""")

        /** [OPERATION_CHARS] anchored, so a token must be a name WHOLE rather than merely contain one. */
        val OPERATION_NAME = Regex("^$OPERATION_CHARS$")

        const val DOCUMENTS =
            "accounting/document/src/main/kotlin/org/example/project/features/document/data/service/DocumentManagementServiceImpl.kt"

        const val TIME_TRACKING =
            "backoffice/timetracking/src/main/kotlin/org/example/project/features/timetracking/data/service/TimeTrackingServiceImpl.kt"
    }
}
