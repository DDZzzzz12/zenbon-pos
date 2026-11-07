package org.example.project.admin.features.finance.presentation

/**
 * What the Buchungsbeginn banner and its dialog need, on whichever screen they are shown.
 *
 * [showBanner] mirrors the server's `needsAcknowledgement` — durable, and true on **every** read
 * until a manager confirms the date. It deliberately replaced a one-shot flag: that one was true on
 * exactly one response in the system's lifetime, so a reload at the wrong moment lost the banner
 * for good, and once merely listing invoices was enough to create the default, whichever list
 * loaded first consumed it.
 *
 * [isSaving] is the dialog's own in-flight flag, kept apart from the hosting screen's, so a save
 * here cannot grey out that screen's unrelated actions.
 */
data class BookkeepingStartUiState(
    val startDate: String? = null,
    val showBanner: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
) {
    /**
     * Whether the quiet *Buchungsbeginn: …* line shows in place of the banner.
     *
     * True whenever the date is known and the banner is not up — i.e. after it was acknowledged. The
     * banner used to be the only place the date could be seen or changed, so dismissing it removed
     * both for good.
     */
    val showCurrentDate: Boolean
        get() = !showBanner && startDate != null
}
