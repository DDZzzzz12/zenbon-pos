package org.example.project.features.scheduler.presentation.components

/**
 * The next-run value a job card may show. A disabled job keeps a placeholder `nextRunAt` in the
 * database (the seeder computes one so the row is well-formed), but it will not run, so showing it
 * would promise a run that never happens. Returns null (rendered as `EMPTY_VALUE`, "—") when the job
 * is disabled. Used by the list row and the job sheet alike, so the two never disagree (NEW-15).
 */
fun <T : Any> visibleNextRun(
    enabled: Boolean,
    nextRunAt: T?,
): T? = if (enabled) nextRunAt else null
