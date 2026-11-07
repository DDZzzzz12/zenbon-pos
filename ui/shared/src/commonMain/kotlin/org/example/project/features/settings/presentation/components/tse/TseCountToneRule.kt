package org.example.project.features.settings.presentation.components.tse

/** A count is an alert only when it counts failures and there is at least one. */
fun tseCountTone(
    failure: Boolean,
    count: Long,
): TseCountTone = if (failure && count > 0) TseCountTone.Alert else TseCountTone.Neutral
