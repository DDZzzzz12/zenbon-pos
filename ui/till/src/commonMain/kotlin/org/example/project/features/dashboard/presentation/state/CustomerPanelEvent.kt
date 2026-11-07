package org.example.project.features.dashboard.presentation.state

import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto

sealed interface CustomerPanelEvent {
    data object Dismiss : CustomerPanelEvent

    data class SelectCustomer(
        val customer: CustomerDto.CustomerResponseDto,
    ) : CustomerPanelEvent

    data class SelectCustomerById(
        val customerId: String,
    ) : CustomerPanelEvent

    data class SelectAddress(
        val customer: CustomerDto.CustomerResponseDto,
        val address: CustomerAddressDto.CustomerAddressResponseDto,
        val isDelivery: Boolean,
    ) : CustomerPanelEvent

    data object Confirm : CustomerPanelEvent

    data object ShowAddCustomerDialog : CustomerPanelEvent

    data object HideAddCustomerDialog : CustomerPanelEvent

    data class CreateCustomer(
        val firstName: String,
        val lastName: String,
        val phone: String?,
        val email: String?,
        val notes: String?,
        val marketingConsent: Boolean,
        val dataProcessingConsent: Boolean,
    ) : CustomerPanelEvent

    data object ShowAddAddressDialog : CustomerPanelEvent

    data object HideAddAddressDialog : CustomerPanelEvent

    data class CreateAddress(
        val street: String,
        val houseNumber: String,
        val apartment: String?,
        val postalCode: String,
        val city: String,
        val additionalInfo: String?,
        val isDefault: Boolean,
    ) : CustomerPanelEvent

    data object ClearCustomer : CustomerPanelEvent

    data class ToggleDelivery(
        val enabled: Boolean,
    ) : CustomerPanelEvent

    data object RemoveAssignment : CustomerPanelEvent

    data class UpdateCustomer(
        val customerId: String,
        val phone: String?,
        val email: String?,
    ) : CustomerPanelEvent
}
