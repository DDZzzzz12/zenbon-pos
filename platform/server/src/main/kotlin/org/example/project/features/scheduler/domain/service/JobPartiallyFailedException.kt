package org.example.project.features.scheduler.domain.service

/**
 * Thrown by a [ScheduledJobExecutor] whose run did some of its work but not all of it — one DATEV
 * export type failed while the others went through, or one period could not be written.
 *
 * Such a run is **not** a success: `SchedulerEngine` records it as `FAILED`, with [resultMessage]
 * (what did get done) as the result and [failedParts] as the error, so the run history and the
 * job's last status both say so. Before this, the DATEV executor folded its failures into the
 * result text of a `SUCCESS` run, and the admin screen showed "Status: SUCCESS" for a run that
 * had failed a Kassenbuch period (admin16 NOTE-7).
 *
 * There is deliberately no `PARTIAL` status: `job_executions.status` is constrained to
 * RUNNING/SUCCESS/FAILED/SKIPPED, and to an operator "partly failed" needs the same action as
 * "failed" — look at what failed.
 */
class JobPartiallyFailedException(
    val resultMessage: String,
    val failedParts: List<String>,
) : RuntimeException("Failed: ${failedParts.joinToString("; ")}")
