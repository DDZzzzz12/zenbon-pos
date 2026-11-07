package org.example.project.ui.screens.dashboard.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.dashboard.presentation.components.customer.AddAddressDialog
import org.example.project.features.dashboard.presentation.components.customer.AddCustomerDialog
import org.example.project.features.dashboard.presentation.components.customer.CustomerReviewContent
import org.example.project.features.dashboard.presentation.state.CustomerPanelEvent
import org.example.project.features.dashboard.presentation.state.TableOperationsUiState
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.components.PosNavIconButton

/**
 * Customer and address selection panel that slides in from the right side.
 *
 * Provides a multi-step interface for selecting customers and delivery addresses
 * with search functionality and the ability to add new customers and addresses.
 *
 * @param uiState The table operations UI state containing panel visibility, customer dialog state, selected customer/address, and delivery flag
 * @param currentLanguage Current application language
 * @param onEvent Unified event callback for all customer panel interactions
 * @param modifier Modifier for customization
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerAddressSelectionPanel(
    modifier: Modifier = Modifier,
    windowSize: WindowSizeClass,
    uiState: TableOperationsUiState,
    currentLanguage: Language,
    onEvent: (CustomerPanelEvent) -> Unit,
) {
    val visible = uiState.showCustomerPanel
    val state = uiState.customerDialogState
    val selectedCustomer = uiState.selectedCustomer
    val selectedAddress = uiState.selectedAddress
    val isDeliverySession = uiState.isDeliverySession
    // Scrim overlay that dismisses panel when clicked
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource =
                            remember {
                                androidx.compose.foundation.interaction
                                    .MutableInteractionSource()
                            },
                        indication = null,
                    ) {
                        onEvent(CustomerPanelEvent.Dismiss)
                    },
        )
    }

    // Panel that slides in from right
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        val isCustomerStep = selectedCustomer == null

        Scaffold(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (windowSize.isCompact()) 1f else 0.6f),
            topBar = {
                TopAppBar(
                    title = {
                        PosMarqueeText(
                            text =
                                if (isCustomerStep) {
                                    str(CustomerAddressKeys.selectCustomerTitle)
                                } else {
                                    str(CustomerAddressKeys.customerDetailsTitle)
                                },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        if (selectedCustomer != null) {
                            PosNavIconButton(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = str(CustomerAddressKeys.back),
                                onClick = { onEvent(CustomerPanelEvent.ClearCustomer) },
                            )
                        }
                    },
                    actions = {
                        PosIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = getString(CommonKeys.close, currentLanguage),
                            onClick = { onEvent(CustomerPanelEvent.Dismiss) },
                        )
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        if (isCustomerStep) {
                            onEvent(CustomerPanelEvent.ShowAddCustomerDialog)
                        } else {
                            onEvent(CustomerPanelEvent.ShowAddAddressDialog)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = if (isCustomerStep) Icons.Default.PersonAdd else Icons.Default.AddLocation,
                        contentDescription =
                            if (isCustomerStep) {
                                str(CustomerAddressKeys.addNewCustomer)
                            } else {
                                str(CustomerAddressKeys.addNewAddress)
                            },
                    )
                }
            },
        ) { paddingValues ->
            // Content area
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                CustomerReviewContent(
                    customer = selectedCustomer,
                    addresses = state.addresses,
                    selectedAddress = selectedAddress,
                    isDeliverySession = isDeliverySession,
                    isTakeawayTable = state.isTakeawayTable,
                    error = state.error,
                    onEvent = onEvent,
                    currentLanguage = currentLanguage,
                )
            }
        }
    }

    // Add Customer Dialog
    if (state.showAddCustomerDialog) {
        AddCustomerDialog(
            onDismiss = { onEvent(CustomerPanelEvent.HideAddCustomerDialog) },
            onSave = { firstName, lastName, phone, email, notes, marketingConsent, dataProcessingConsent ->
                onEvent(
                    CustomerPanelEvent.CreateCustomer(firstName, lastName, phone, email, notes, marketingConsent, dataProcessingConsent),
                )
            },
            isLoading = state.isSearching,
            error = state.error,
        )
    }

    // Add Address Dialog
    if (state.showAddAddressDialog) {
        val customerId = selectedCustomer?.id ?: ""
        AddAddressDialog(
            customerId = customerId,
            onDismiss = { onEvent(CustomerPanelEvent.HideAddAddressDialog) },
            onSave = { street, houseNumber, apartment, postalCode, city, additionalInfo, isDefault ->
                onEvent(CustomerPanelEvent.CreateAddress(street, houseNumber, apartment, postalCode, city, additionalInfo, isDefault))
            },
            isLoading = state.isSearching,
            error = state.error,
        )
    }
}
