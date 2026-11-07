package org.example.project.admin.features.terminal.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.StringKey

/**
 * Why "TSE-Client abmelden" is not available for [terminal] yet, or null when it is.
 *
 * Mirrors the server's guards on `POST /{id}/tse-deregister` that the list already knows about —
 * deactivated, no bound device. The server re-checks those and also refuses unfinished TSE work and
 * the last client of a NORMAL-mode TSE, which only it can see.
 */
fun tseDeregisterBlockReason(terminal: TerminalDto.TerminalSummaryDto): StringKey? =
    when {
        terminal.isActive -> AdminStringKeys.TerminalTseDeregisterNeedsDeactivate
        terminal.bound -> AdminStringKeys.TerminalTseDeregisterNeedsRevoke
        else -> null
    }
