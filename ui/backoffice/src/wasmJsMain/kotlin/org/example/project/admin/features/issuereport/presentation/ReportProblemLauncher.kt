package org.example.project.admin.features.issuereport.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether "Report a problem" is open, owned ABOVE the compact/wide shell switch.
 *
 * The dialog is launched from the user menu, which lives in the sidebar on a wide window and in the
 * drawer on a phone. Neither survives crossing 600dp, so a flag remembered there closed an open
 * report and dropped its draft (browser pass 2, R9f). `AuthenticatedApp` remembers one launcher, both
 * shells only call [open] / [openAfter], and the one [ReportProblemDialog] is hosted outside the
 * branch: a resize no longer disposes it, so the view model keeps the typed title, description and
 * log choice. Remembered inside the authenticated app, so a sign-out discards it.
 */
class ReportProblemLauncher {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    /**
     * Opens once [beforeOpening] (the phone drawer closing) has finished, so the dialog never sits
     * over a drawer still sliding shut, and opens even when that step is interrupted, e.g. by a
     * gesture that cancels the drawer animation.
     */
    suspend fun openAfter(beforeOpening: suspend () -> Unit) {
        try {
            beforeOpening()
        } finally {
            open()
        }
    }
}
