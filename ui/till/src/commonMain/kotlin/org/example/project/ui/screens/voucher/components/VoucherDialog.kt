/**
 * Unified Voucher Dialog component.
 *
 * Single dialog handling all voucher creation flows:
 * 1. Counter sale: voucher sold directly at the counter, paid immediately
 * 2. Table Session: Adding voucher to existing table order
 * 3. Success: Showing created voucher details with print option
 *
 * This component encapsulates the VoucherCreationViewModel internally
 * to keep the parent screen clean and focused on table operations.
 *
 * Features:
 * - Mode-based UI (Counter sale / Table / Success)
 * - Voucher value input with validation
 * - Payment type selection (counter sale only)
 * - Optional tip amount (counter sale only)
 * - Print confirmation after creation
 * - Automatic voucher system check
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.voucher.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.PaymentType
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.PaymentTypeButton
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.VoucherKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.voucher.presentation.state.TableVoucherState
import org.example.project.features.voucher.presentation.viewmodel.VoucherCreationViewModel
import org.example.project.ui.auth.holds
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

/**
 * Voucher dialog mode.
 */
enum class VoucherDialogMode {
    /** Counter sale voucher with immediate payment */
    COUNTER_SALE,

    /** Adding voucher to table session */
    TABLE_SESSION,

    /** Showing created voucher with print option */
    SUCCESS,
}

/**
 * Unified Voucher Dialog.
 *
 * Handles all voucher creation scenarios in a single dialog component.
 *
 * @param isVisible Whether dialog is visible
 * @param mode Current dialog mode (Counter sale / Table / Success)
 * @param selectedTable Optional table for table voucher (contains sessionId and tableNumber)
 * @param language Current app language
 * @param onDismiss Callback when dialog is dismissed
 * @param onVoucherCreated Optional callback after successful voucher creation
 */
@Composable
fun VoucherDialog(
    isVisible: Boolean,
    mode: VoucherDialogMode,
    selectedTable: RTable? = null,
    language: Language,
    onDismiss: () -> Unit,
    onVoucherCreated: ((VoucherDto.VoucherResponseDto) -> Unit)? = null,
    isVoucherEnabled: Boolean = false,
    viewModel: VoucherCreationViewModel = koinViewModel(),
) {
    // Check voucher system status
    if (!isVisible || !isVoucherEnabled) return

    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    // Show loading notification for voucher creation
    val isVoucherLoading = uiState.counterSale.isLoading || (uiState.tableVoucher?.isLoading == true)
    var voucherLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isVoucherLoading) {
        if (isVoucherLoading) {
            voucherLoadingId =
                overlay.showLoading(
                    title = getString(VoucherKeys.voucherTitle, language),
                    message = getString(VoucherKeys.creatingVoucher, language),
                )
        } else {
            voucherLoadingId?.let {
                overlay.dismissById(it)
                voucherLoadingId = null
            }
        }
    }

    // Initialize mode when dialog opens
    LaunchedEffect(mode, selectedTable) {
        if (isVisible) {
            when (mode) {
                VoucherDialogMode.COUNTER_SALE -> {
                    viewModel.loadPaymentTypes()
                    viewModel.loadPrintAvailability()
                }
                VoucherDialogMode.TABLE_SESSION -> {
                    selectedTable?.currentSessionId?.let { sessionId ->
                        viewModel.openTableVoucherDialog(sessionId, selectedTable.tableNumber)
                    }
                }
                VoucherDialogMode.SUCCESS -> {
                    // Success mode handled by internal state
                }
            }
        }
    }

    // Determine current mode based on state
    val currentMode =
        when {
            uiState.counterSale.showPrintDialog && uiState.counterSale.createdVoucher != null -> VoucherDialogMode.SUCCESS
            uiState.tableVoucher?.isSuccess == true -> VoucherDialogMode.SUCCESS
            uiState.tableVoucher != null -> VoucherDialogMode.TABLE_SESSION
            else -> VoucherDialogMode.COUNTER_SALE
        }

    // The UI half of the receipt-print guard (CLAUDE.md rule 8): `POST /api/v1/bills/{id}/print` is
    // `Cap(PRINTER_WRITE)` and refuses with 409 PRINTING_DISABLED while the shop's printing is off.
    val canPrintReceipt = holds(Capability.PRINTER_WRITE)

    PosErrorWatcher(
        error = uiState.counterSale.printError,
        title = str(VoucherKeys.voucherTitle),
        onClear = viewModel::clearPrintError,
        displayMode = DisplayMode.DIALOG,
    )

    // A printed receipt is confirmed, then the dialog closes like "Überspringen" does.
    val receiptPrintedMessage = str(VoucherKeys.receiptPrinted)
    LaunchedEffect(uiState.counterSale.receiptPrinted) {
        val voucher = uiState.counterSale.createdVoucher
        if (uiState.counterSale.receiptPrinted && voucher != null) {
            overlay.showSuccess(title = receiptPrintedMessage, message = receiptPrintedMessage)
            viewModel.closePrintDialog()
            onVoucherCreated?.invoke(voucher)
            onDismiss()
        }
    }

    when (currentMode) {
        VoucherDialogMode.SUCCESS -> {
            // Get voucher from appropriate state
            val voucher = uiState.counterSale.createdVoucher ?: uiState.tableVoucher?.createdVoucher

            if (voucher != null) {
                VoucherSuccessContent(
                    voucher = voucher,
                    language = language,
                    // Only a counter sale has a bill yet; a table voucher prints with its table's bill.
                    printOffered = voucher.billId != null && uiState.printOffered && canPrintReceipt,
                    isPrinting = uiState.counterSale.isPrinting,
                    onDismiss = {
                        viewModel.dismissCounterSaleDialog()
                        viewModel.dismissTableVoucherDialog()
                        onDismiss()
                    },
                    onPrintClick = viewModel::printVoucherReceipt,
                    onSkipClick = {
                        viewModel.closePrintDialog()
                        viewModel.dismissTableVoucherDialog()
                        onVoucherCreated?.invoke(voucher)
                        onDismiss()
                    },
                )
            }
        }
        VoucherDialogMode.TABLE_SESSION -> {
            val tableState = uiState.tableVoucher
            if (tableState != null) {
                TableVoucherContent(
                    state = tableState,
                    language = language,
                    onDismiss = {
                        viewModel.dismissTableVoucherDialog()
                        onDismiss()
                    },
                    onValueChange = viewModel::setTableVoucherValue,
                    onAddClick = {
                        viewModel.createTableVoucher()
                    },
                )
            }
        }
        VoucherDialogMode.COUNTER_SALE -> {
            CounterSaleVoucherContent(
                state = uiState.counterSale,
                paymentTypes = uiState.paymentTypes,
                language = language,
                onDismiss = {
                    viewModel.dismissCounterSaleDialog()
                    onDismiss()
                },
                onValueChange = viewModel::setCounterSaleValue,
                onTipChange = viewModel::setCounterSaleTip,
                onPaymentTypeSelect = viewModel::selectCounterSalePaymentType,
                onCreateClick = viewModel::createCounterSaleVoucher,
            )
        }
    }
}

/**
 * Counter sale voucher dialog content.
 */
@Composable
private fun CounterSaleVoucherContent(
    state: org.example.project.features.voucher.presentation.state.CounterSaleVoucherState,
    paymentTypes: List<PaymentType>,
    language: Language,
    onDismiss: () -> Unit,
    onValueChange: (String) -> Unit,
    onTipChange: (String) -> Unit,
    onPaymentTypeSelect: (PaymentType) -> Unit,
    onCreateClick: () -> Unit,
) {
    val filteredPaymentTypes = paymentTypes.filterNot { it.name == "VOUCHER" || it.name == "GUTSCHEIN" }

    PosDialog(
        onDismiss = onDismiss,
        title = str(VoucherKeys.counterSaleTitle),
        titleIcon = Icons.Default.Payment,
        maxWidth = 600.dp,
        dismissOnClickOutside = false,
        footer = {
            PosSecondaryButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
                enabled = !state.isLoading,
            )
            PosButton(
                text = str(VoucherKeys.createVoucher),
                onClick = onCreateClick,
                enabled = state.isValid,
                isLoading = state.isLoading,
            )
        },
    ) {
        // The refusal stands at the top, above the form, so it is seen without scrolling
        // (emu20 FAIL-B: it sat below the fold and a refused tap looked like nothing happened).
        state.errorMessage?.let { VoucherDialogError(it) }

        // Voucher Value Input
        PosTextField(
            value = state.voucherValue,
            onValueChange = onValueChange,
            label = str(VoucherKeys.voucherValue),
            placeholder = str(VoucherKeys.voucherValueHint),
            keyboardType = KeyboardType.Decimal,
            isError = state.errors.containsKey("voucherValue"),
            supportingText = {
                state.errors["voucherValue"]?.let {
                    Text(str(it), color = MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            prefix = { Text("€") },
        )

        // Payment Type Selection
        Text(
            text = str(VoucherKeys.selectPaymentMethod),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )

        // Payment Type Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            items(filteredPaymentTypes) { paymentType ->
                PaymentTypeButton(
                    paymentType = paymentType,
                    isSelected = state.selectedPaymentType?.id == paymentType.id,
                    onClick = { onPaymentTypeSelect(paymentType) },
                    language = language,
                )
            }
        }

        // Tip Amount Input (Optional)
        PosTextField(
            value = state.tipAmount,
            onValueChange = onTipChange,
            label = str(VoucherKeys.tipAmount),
            placeholder = str(VoucherKeys.tipAmountHint),
            keyboardType = KeyboardType.Decimal,
            isError = state.errors.containsKey("tipAmount"),
            supportingText = {
                state.errors["tipAmount"]?.let {
                    Text(str(it), color = MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            prefix = { Text("€") },
            imeAction = ImeAction.Done,
        )

        // Total Amount Display
        PosCard(variant = PosCardVariant.Plain) {
            body {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(VoucherKeys.totalAmount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = state.totalAmount.toFormattedCurrency(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Table Voucher dialog content.
 */
@Composable
private fun TableVoucherContent(
    state: TableVoucherState,
    language: Language,
    onDismiss: () -> Unit,
    onValueChange: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(VoucherKeys.tableVoucherTitle),
        titleIcon = Icons.Default.TableRestaurant,
        maxWidth = 600.dp,
        dismissOnClickOutside = false,
        footer = {
            PosSecondaryButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
                enabled = !state.isLoading,
            )
            PosButton(
                text = str(VoucherKeys.addToOrder),
                onClick = onAddClick,
                enabled = state.isValid,
                isLoading = state.isLoading,
                icon = Icons.Default.Add,
            )
        },
    ) {
        state.errorMessage?.let { VoucherDialogError(it) }

        // Table Context
        PosCard(variant = PosCardVariant.Plain) {
            body {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(VoucherKeys.tableName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = state.tableName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Info Text
        Text(
            text = str(VoucherKeys.addingToTable, state.tableName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Voucher Value Input
        PosTextField(
            value = state.voucherValue,
            onValueChange = onValueChange,
            label = str(VoucherKeys.voucherValue),
            placeholder = str(VoucherKeys.voucherValueHint),
            keyboardType = KeyboardType.Decimal,
            isError = state.errors.containsKey("voucherValue"),
            supportingText = {
                state.errors["voucherValue"]?.let {
                    Text(str(it), color = MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            prefix = { Text("€") },
            imeAction = ImeAction.Done,
        )
    }
}

/**
 * Voucher Success dialog content.
 */
@Composable
private fun VoucherSuccessContent(
    voucher: VoucherDto.VoucherResponseDto,
    language: Language,
    printOffered: Boolean,
    isPrinting: Boolean,
    onDismiss: () -> Unit,
    onPrintClick: () -> Unit,
    onSkipClick: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(VoucherKeys.voucherCreatedSuccessfully),
        titleIcon = Icons.Default.CheckCircle,
        maxWidth = 480.dp,
        footer = {
            if (printOffered) {
                PosSecondaryButton(
                    text = str(VoucherKeys.skip),
                    onClick = onSkipClick,
                    enabled = !isPrinting,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PosButton(
                    text = str(VoucherKeys.printReceipt),
                    onClick = onPrintClick,
                    icon = Icons.Default.Print,
                    isLoading = isPrinting,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // No receipt to offer: printing is off, the role may not print, or the voucher is
                // billed later with its table.
                PosButton(
                    text = str(CommonKeys.close),
                    onClick = onSkipClick,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        // Voucher Number Display
        PosCard(variant = PosCardVariant.Plain) {
            body {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = str(VoucherKeys.voucherNumber),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = voucher.voucherNumber,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Voucher Value Display
        PosCard(variant = PosCardVariant.Plain) {
            body {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(VoucherKeys.voucherValue),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = voucher.originalValue.toFormattedCurrency(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // Print Question
        if (printOffered) {
            Text(
                text = str(VoucherKeys.printVoucherQuestion),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A refused or failed voucher action, shown at the TOP of the dialog in the error colours: its title
 * and message, so a 403 ("Ihre Berechtigungsgruppe erlaubt diese Aktion nicht.") is read at once.
 */
@Composable
private fun VoucherDialogError(error: UiError) {
    PosCard(variant = PosCardVariant.Plain, containerColor = MaterialTheme.colorScheme.errorContainer) {
        body {
            Text(
                text = str(error.titleKey),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
