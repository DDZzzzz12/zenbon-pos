package org.example.project.ui.guard

/** A leave the user has been asked about. [sources] are the dirty surfaces in scope; [then] is the leave itself. */
class PendingLeave(
    val sources: List<DirtySource>,
    val then: () -> Unit,
)
