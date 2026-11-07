package org.example.project.core.presentation.components.customer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.CustomerApiClient
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosTextField
import org.koin.compose.koinInject

/**
 * Self-contained customer search field with autocomplete dropdown.
 *
 * Supports both free-text entry and customer selection from search results.
 * Manages its own search state internally using CustomerApiClient via Koin.
 *
 * A suggestion is applied ONLY by tapping it; the typed text is never replaced otherwise. With
 * [inlineSuggestions] the list is laid out under the field (pushing the next fields down) instead of
 * floating over them, and it closes as soon as the field loses focus. A form whose next field sits
 * right under this one needs that: the floating list covered the next field, so the tap meant for
 * it picked a customer and overwrote what had been typed (emu14 NOTE-4, the invoice buyer name).
 */
@Composable
fun CustomerSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onCustomerSelected: (CustomerDto.CustomerSummaryDto) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    onCustomerDetailsFetched: ((CustomerDto.CustomerResponseDto) -> Unit)? = null,
    inlineSuggestions: Boolean = false,
) {
    val logger = remember { Logger.withTag("CustomerSearchField") }
    val customerApiClient: CustomerApiClient = koinInject()
    val coroutineScope = rememberCoroutineScope()

    var suggestions by remember { mutableStateOf<List<CustomerDto.CustomerSummaryDto>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    val select: (CustomerDto.CustomerSummaryDto) -> Unit = { customer ->
        onCustomerSelected(customer)
        onValueChange(customer.fullName)
        showSuggestions = false
        suggestions = emptyList()

        if (onCustomerDetailsFetched != null) {
            coroutineScope.launch {
                logger.d { "CustomerSearchField: fetching details for customer=${customer.customerNumber}" }
                runCatchingApi { customerApiClient.getCustomerById(customer.id) }
                    .onSuccess { /* details fetched */ }
                    .onError { uiError ->
                        logger.e { "CustomerSearchField: detail fetch failed: $uiError" }
                    }
            }
        }
    }
    val searchField: @Composable () -> Unit = {
        PosTextField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                if (newValue.length >= 2) {
                    coroutineScope.launch {
                        logger.d { "CustomerSearchField: searching for '$newValue'" }
                        runCatchingApi { customerApiClient.searchCustomers(query = newValue, limit = 10) }
                            .onSuccess { results ->
                                suggestions = results
                                showSuggestions = results.isNotEmpty()
                            }.onError { uiError ->
                                logger.e { "CustomerSearchField: search failed: $uiError" }
                                suggestions = emptyList()
                                showSuggestions = false
                            }
                    }
                } else {
                    suggestions = emptyList()
                    showSuggestions = false
                }
            },
            label = label,
            enabled = enabled,
            leadingIcon = leadingIcon,
            // Inline: leaving the field closes the list and keeps what was typed — never "picks" for
            // the user. (The floating list is a focusable popup that takes focus when it opens, so
            // it closes on its own outside tap instead.)
            modifier =
                Modifier.fillMaxWidth().then(
                    if (inlineSuggestions) Modifier.onFocusChanged { if (!it.hasFocus) showSuggestions = false } else Modifier,
                ),
        )
    }

    if (inlineSuggestions) {
        Column(modifier) {
            searchField()
            if (showSuggestions && suggestions.isNotEmpty()) {
                PosCard(variant = PosCardVariant.Plain, modifier = Modifier.fillMaxWidth()) {
                    body {
                        Column {
                            suggestions.forEach { customer ->
                                PosDropdownItem(
                                    text = customer.suggestionText(),
                                    onClick = { select(customer) },
                                    // Not a focus target: a click must not move focus off the field
                                    // first, or the list closes under the pointer before it lands.
                                    modifier = Modifier.focusProperties { canFocus = false },
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Box(modifier) {
        searchField()

        PosDropdown(
            expanded = showSuggestions && suggestions.isNotEmpty(),
            onDismissRequest = {
                showSuggestions = false
            },
        ) {
            suggestions.forEach { customer ->
                PosDropdownItem(text = customer.suggestionText(), onClick = { select(customer) })
            }
        }
    }
}

/** A suggestion row: the name, and the phone number when there is one to tell namesakes apart. */
private fun CustomerDto.CustomerSummaryDto.suggestionText(): String = if (!phone.isNullOrBlank()) "$fullName - $phone" else fullName
