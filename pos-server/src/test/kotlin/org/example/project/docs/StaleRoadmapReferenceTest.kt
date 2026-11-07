package org.example.project.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Documentation may describe what the code does. It may not promise what a future slice will do.
 *
 * Seven statements of the second kind were found and corrected across two slices in August 2026 —
 * a KDoc calling a target "unreachable until slice 4 creates the table" whose table had existed for
 * weeks, a route doc saying "contract instalment targets are not supported yet" beside a handler
 * that supported them, a column doc claiming "nothing maintains it yet" next to the writer that
 * maintained it. Each was accurate when written. None had any reason to be re-read when the thing
 * it promised arrived, so each one quietly became a lie that a future reader would have believed.
 *
 * The pattern is narrow on purpose: it matches **roadmap language**, not absence language. Saying a
 * capability is missing is fine and often necessary — the manual's "Noch in Arbeit" notice about
 * the IBAN warning is a real limitation an operator must know. What rots is the schedule attached
 * to it: *which* slice, and that it is *next*. Write the limitation, drop the promise, and the
 * sentence stays true until the code changes rather than until the sprint does.
 *
 * There is deliberately **no allowlist**. Every match this has ever produced was a defect, and an
 * escape hatch on a rule with no known exception is an invitation to use it instead of rewording
 * one sentence.
 */
class StaleRoadmapReferenceTest {
    /**
     * Each pattern names a development milestone rather than a behaviour.
     *
     * Rejected during design, with their measured false-positive counts, so nobody re-adds them:
     * `noch nicht` (15 of 16 manual hits describe runtime state — "noch nicht bezahlt", "falls noch
     * nicht geändert"), `is not yet` (12 of 12 Kotlin hits are runtime state — "config is not yet
     * loaded"), `not yet supported` (11 of 13 are a stable block of DATEV field placeholders), and
     * a bare `slice \d` (most hits explain why code is as it is, in the past tense, which is
     * exactly what a KDoc should do).
     */
    private val roadmapPatterns =
        listOf(
            Regex("""until slice""", RegexOption.IGNORE_CASE),
            Regex("""unreachable until""", RegexOption.IGNORE_CASE),
            Regex("""slice \d\w*\s+(unlocks|creates|adds|teaches|will)""", RegexOption.IGNORE_CASE),
            // Future tense about a numbered slice, however far the verb sits from it. Added after
            // "Slice 5's matcher is what will pass one" survived the patterns above and was found by
            // hand — slice 5 had shipped, and its matcher passes null. The `[^.\n]` bound keeps the
            // match inside one sentence so an unrelated later "will" cannot be dragged in.
            Regex("""slice \d\w*('s)?[^.\n]{0,60}\bwill\b""", RegexOption.IGNORE_CASE),
            Regex("""nothing maintains""", RegexOption.IGNORE_CASE),
            Regex("""not supported yet""", RegexOption.IGNORE_CASE),
            Regex("""noch nicht unterstützt""", RegexOption.IGNORE_CASE),
            Regex("""der nächste Schritt""", RegexOption.IGNORE_CASE),
            Regex("""the next step""", RegexOption.IGNORE_CASE),
            Regex("""in a later slice""", RegexOption.IGNORE_CASE),
            Regex("""its own slice""", RegexOption.IGNORE_CASE),
        )

    @Test
    fun `no source or manual page promises what a later slice will do`() {
        // This file names every pattern it searches for, so it is the one source that must not be
        // scanned. Excluded by filename rather than by a marker comment: a marker is one copy-paste
        // away from silencing a real offender.
        val kotlin = repoFiles("kt") { it.path.endsWith(".kt") && it.name != "StaleRoadmapReferenceTest.kt" }
        val manual = repoFiles("manual html") { it.path.endsWith(".html") && "resources/manual" in it.path }

        // Guard the guard. A file listing that silently comes back empty turns this whole test green
        // and useless, which is the failure mode a gate can least afford — it would still be reported
        // as passing in every verification sweep.
        assertTrue(kotlin.size > 500, "expected to scan the repo's Kotlin sources, found ${kotlin.size}")
        assertTrue(manual.size > 10, "expected to scan the manual pages, found ${manual.size}")

        // Distinct by site: one sentence can trip three patterns at once ("unreachable until slice
        // 4b unlocks it" trips all three), and reporting it three times reads like three defects.
        val offences = (kotlin + manual).flatMap { it.offences() }.distinctBy { it.substringBefore(" \u2014 ") }

        assertTrue(
            offences.isEmpty(),
            "Documentation promises what a later slice will do. Such a sentence is true when written " +
                "and silently false the day that slice lands, because nothing brings a reader back to " +
                "it. Say what the code does or does not do today and leave the schedule out:\n\n" +
                offences.joinToString("\n"),
        )
    }

    private fun File.offences(): List<String> {
        val text = readText()
        return roadmapPatterns
            .flatMap { it.findAll(text) }
            .map { match ->
                val line = text.take(match.range.first).count { it == '\n' } + 1
                val context = text.substring(match.range.first, minOf(text.length, match.range.last + 60))
                "  ${relativeToRepo()}:$line — ${context.replace(Regex("""\s+"""), " ").trim()}"
            }
    }

    /**
     * The set of files this checkout is responsible for, as `git` defines it: everything tracked in
     * the index plus everything untracked that is not ignored. Read from `git`, not from a tree walk.
     *
     * A tree walk is the wrong instrument here. `.claude/worktrees/` holds other sessions' checkouts
     * of this same repository nested inside the working tree, so a walk scanned *their* branches and
     * failed this gate on code that is not ours and that we cannot fix — the two hits that prompted
     * this change were both in a worktree named `bill-document-unification`. Excluding that name
     * would have fixed exactly one worktree; the next one is called something else. `git` already
     * knows the answer, and it excludes generated output (`build/`, `.gradle/`, `.kotlin/`,
     * `node_modules/`) and `.git` itself for the same reason, without a hand-maintained list.
     *
     * Untracked-but-not-ignored files are included deliberately: a source file added in the working
     * tree and not yet `git add`ed is still this checkout's code, and the gate should see it.
     */
    private fun repoFiles(
        what: String,
        accept: (File) -> Boolean,
    ): List<File> {
        val root = File("..")
        assertTrue(root.isDirectory, "cannot find the repo root; working dir is ${File(".").absolutePath}")
        return gitListedPaths(root)
            .map { File(root, it) }
            .filter { it.isFile && accept(it) }
            .also { assertTrue(it.isNotEmpty(), "found no $what to scan") }
    }

    /**
     * `--cached` = tracked, `--others --exclude-standard` = untracked but not ignored. A failure to
     * run `git` is raised rather than swallowed: an empty list would turn this gate green and useless.
     */
    private fun gitListedPaths(root: File): List<String> {
        val process =
            ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard")
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue(exit == 0, "git ls-files failed with exit $exit in ${root.absolutePath}:\n$output")
        return output.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun File.relativeToRepo(): String = path.removePrefix("../")
}
