package org.example.project.features.reservation.presentation.state

import org.example.project.core.domain.dto.reservation.ReservationRuleDto
import org.example.project.core.presentation.error.UiError

data class ReservationRulesState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: String? = null,
    val rules: List<ReservationRuleDto.ResponseDto> = emptyList(),
    val showCreateDialog: Boolean = false,
    val editingRule: ReservationRuleDto.ResponseDto? = null,
)

sealed interface ReservationRulesEvent {
    data class LoadRules(
        val startDate: String,
        val endDate: String,
    ) : ReservationRulesEvent

    data class DeleteRule(
        val id: Long,
    ) : ReservationRulesEvent

    data object ShowCreateDialog : ReservationRulesEvent

    data object HideCreateDialog : ReservationRulesEvent

    data class EditRule(
        val rule: ReservationRuleDto.ResponseDto,
    ) : ReservationRulesEvent

    data object Refresh : ReservationRulesEvent

    data class CreateRule(
        val request: ReservationRuleDto.CreateRequestDto,
    ) : ReservationRulesEvent

    data class UpdateRule(
        val id: Long,
        val request: ReservationRuleDto.UpdateRequestDto,
    ) : ReservationRulesEvent
}
