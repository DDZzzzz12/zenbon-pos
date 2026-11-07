package org.example.project.features.update.data.service

import org.example.project.features.update.domain.models.PreflightVerdict

/**
 * Decides whether an update may be installed right now, and names the blocker when it may not.
 *
 * Every probe is injected as a lambda. That keeps this class a pure decision -- it owns the
 * *order* of the guards and the two boundary conventions, nothing else -- and lets the whole
 * matrix be tested without a database, a scheduler or a filesystem. It is a deliberate exception
 * to the "at most three parameters" guidance: the alternative, a parameter object, would only
 * rename the same seven probes without making a call site any clearer, and each one has to stay
 * individually substitutable for the tests to prove that no guard is shadowed by an earlier one.
 *
 * ### Boundaries
 *
 * The disk threshold is **inclusive of the passing side**:
 * - disk: free space exactly equal to the requirement passes. The requirement is computed to
 *   include headroom already; making it exclusive would silently demand one byte more than was
 *   asked for.
 *
 * ### Supplying the real probes (later task -- deliberately not wired here)
 *
 * - [hasOpenBusiness] -> `OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS)` inside
 *   `dbQuery { }`. That is already **the** single definition of "business is open since the last
 *   Kassenabschluss", shared with the Z-Bon and Stammdaten paths. Do not write a second query --
 *   a preflight that disagreed with the Z-Bon path about what "open" means would be worse than no
 *   preflight at all.
 * - [hasOpenTseWork] -> the existing `TerminalFiscalGateway.hasPendingSigning` predicate used by
 *   `TerminalServiceImpl`.
 * - [countRunningJobs] -> running `JobExecution`s **excluding this job's own row**. The update
 *   check runs as a scheduled job, so its own execution is `RUNNING` by definition while this
 *   evaluates. Forgetting that exclusion blocks every update forever, and does so silently: the
 *   verdict is a perfectly plausible `JOB_RUNNING` every single night.
 */
class UpdatePreflight(
    private val hasOpenBusiness: suspend () -> Boolean,
    private val countOpenSessions: suspend () -> Int,
    private val hasOpenTseWork: suspend () -> Boolean,
    private val countRunningJobs: suspend () -> Int,
    private val freeDiskBytes: suspend () -> Long,
    private val requiredBytes: suspend () -> Long,
) {
    /**
     * Runs the guards in [PreflightVerdict.Reason] declaration order and returns the first
     * blocker, or [PreflightVerdict.Clear] when none applies.
     *
     * There is deliberately no "has the server been quiet" guard: open bills, open table sessions
     * and unfinished TSE work already say whether the shop is trading, and a request counter only
     * ever held an update back for reasons that were not about the shop's state.
     */
    suspend fun evaluate(): PreflightVerdict {
        if (hasOpenBusiness()) return blocked(PreflightVerdict.Reason.OPEN_BUSINESS)
        if (countOpenSessions() > 0) return blocked(PreflightVerdict.Reason.OPEN_TABLE_SESSION)
        if (hasOpenTseWork()) return blocked(PreflightVerdict.Reason.OPEN_TSE_WORK)
        if (countRunningJobs() > 0) return blocked(PreflightVerdict.Reason.JOB_RUNNING)
        if (freeDiskBytes() < requiredBytes()) return blocked(PreflightVerdict.Reason.INSUFFICIENT_DISK)
        return PreflightVerdict.Clear
    }

    private fun blocked(reason: PreflightVerdict.Reason) = PreflightVerdict.Blocked(reason)
}
