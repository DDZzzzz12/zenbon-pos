package org.example.project.admin.features.documents.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey

/** The steps of a Beleg's Verlauf, in order. Zugeordnet/Gebucht are not here: a document row carries no allocation data. */
enum class DocumentHistoryStep(
    val labelKey: StringKey,
) {
    UPLOADED(AdminStringKeys.BelegflowHistoryUploaded),
    CAPTURED(AdminStringKeys.BelegflowHistoryCaptured),
    REVIEWED(AdminStringKeys.BelegflowHistoryReviewed),
    LINKED(AdminStringKeys.BelegflowHistoryLinked),
}
