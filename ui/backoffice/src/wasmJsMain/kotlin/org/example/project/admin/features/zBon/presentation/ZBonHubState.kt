package org.example.project.admin.features.zBon.presentation

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.presentation.error.UiError
import org.example.project.ui.components.BillDetailTrail

data class ZBonHubState(
    val activeTab: ZBonHubTab = ZBonHubTab.OVERVIEW,
    val zBon: ZBonDto.ZBonResponseDto? = null,
    val zBonLoading: Boolean = false,
    val zBonError: UiError? = null,
    val bills: List<BillDto.BillResponseDto> = emptyList(),
    val billsLoading: Boolean = false,
    val billsError: UiError? = null,
    val sessions: List<TableSessionDto.TableSessionResponseDto> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: UiError? = null,
    val billSearchQuery: String = "",
    val printing: Boolean = false,
    val pdfDownloading: Boolean = false,
    /**
     * Print/PDF failures, kept apart from [zBonError] so a failed action never looks like a failed
     * page load: retrying the former re-sends the action, retrying the latter reloads the screen.
     */
    val actionError: UiError? = null,
    /** The bill whose receipt panel is open over the Bills tab, or null. */
    val selectedBill: BillDto.BillResponseDto? = null,
    /** Bills left behind by following related-bill links in that panel ("Zurück zu RE-…"). */
    val billTrail: BillDetailTrail = BillDetailTrail(),
)

enum class ZBonHubTab { OVERVIEW, BILLS, SESSIONS }
