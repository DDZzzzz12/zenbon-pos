package org.example.project.features.dashboard.presentation.state

import org.example.project.core.domain.dto.customer.CustomerAddressDto

/**
 * State for customer address selection dialog.
 */
data class CustomerAddressSelectionState(
    val isSearching: Boolean = false,
    val addresses: List<CustomerAddressDto.CustomerAddressResponseDto> = emptyList(),
    val showAddCustomerDialog: Boolean = false,
    val showAddAddressDialog: Boolean = false,
    val isDeliverySession: Boolean = false,
    val isTakeawayTable: Boolean = false,
    val error: String? = null,
)
