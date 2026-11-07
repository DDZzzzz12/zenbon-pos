/**
 * Bottom sheet for searching bills by number or QR code scanning.
 *
 * Provides two search methods:
 * 1. Manual entry of bill number
 * 2. QR code scanning (Android only) using TSE QR codes from printed receipts
 *
 * The TSE QR code format is DSFinV-K compliant:
 * V0;kassen-seriennummer;processType;processData;transaktions-nummer;...
 * The transaction number at index 4 uniquely identifies the TSE transaction linked to a bill.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.reports.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isQrScannerAvailable
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.koinInject
import qrscanner.CameraLens
import qrscanner.QrScanner

/**
 * Bottom sheet composable for bill search functionality.
 *
 * Supports manual bill number entry and QR code scanning (Android only).
 * On successful bill lookup, invokes [onBillFound] with the bill data.
 *
 * @param language Current app language for i18n
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param onBillFound Callback with the found bill data
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillSearchBottomSheet(
    language: Language,
    onDismiss: () -> Unit,
    onBillFound: (BillDto.BillResponseDto) -> Unit,
) {
    val billApiClient = koinInject<BillApiClient>()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val showQrTab = isQrScannerAvailable()

    // Tab state: 0 = Manual Entry, 1 = QR Scanner
    var selectedTab by remember { mutableIntStateOf(0) }

    // Manual entry state
    var billNumberInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // QR scanner state
    var flashlightOn by remember { mutableStateOf(false) }
    var qrProcessing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 600.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .keyboardAwarePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title
            Text(
                text = str(ReportsKeys.searchBill),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Tab row (only shown on Android where QR scanner is available)
            if (showQrTab) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(str(ReportsKeys.manualEntry)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(str(ReportsKeys.scanQrCode)) },
                    )
                }
            }

            // Content based on selected tab
            when (selectedTab) {
                0 ->
                    ManualEntryContent(
                        language = language,
                        billNumberInput = billNumberInput,
                        onBillNumberChange = {
                            billNumberInput = it
                            searchError = null
                        },
                        isSearching = isSearching,
                        searchError = searchError,
                        onSearch = {
                            if (billNumberInput.isBlank()) return@ManualEntryContent
                            coroutineScope.launch {
                                isSearching = true
                                searchError = null
                                runCatchingApi { billApiClient.getBillByNumber(billNumberInput.trim()) }
                                    .onSuccess { bill ->
                                        isSearching = false
                                        onBillFound(bill)
                                    }.onError {
                                        isSearching = false
                                        searchError = getString(ReportsKeys.billNotFound, language)
                                    }
                            }
                        },
                    )

                1 ->
                    if (showQrTab) {
                        QrScannerContent(
                            language = language,
                            flashlightOn = flashlightOn,
                            onToggleFlashlight = { flashlightOn = !flashlightOn },
                            qrProcessing = qrProcessing,
                            searchError = searchError,
                            onQrScanned = { scannedData ->
                                if (qrProcessing) return@QrScannerContent
                                qrProcessing = true
                                searchError = null

                                // Parse DSFinV-K QR code: V0;serial;processType;processData;transactionNumber;...
                                val parts = scannedData.split(";")
                                if (parts.size >= 5 && parts[0] == "V0") {
                                    val transactionNumber = parts[4].toLongOrNull()
                                    if (transactionNumber != null) {
                                        coroutineScope.launch {
                                            runCatchingApi { billApiClient.getBillByTseTransactionNumber(transactionNumber) }
                                                .onSuccess { bill ->
                                                    qrProcessing = false
                                                    onBillFound(bill)
                                                }.onError {
                                                    qrProcessing = false
                                                    searchError = getString(ReportsKeys.billNotFound, language)
                                                }
                                        }
                                    } else {
                                        qrProcessing = false
                                        searchError = getString(ReportsKeys.invalidQrCode, language)
                                    }
                                } else {
                                    qrProcessing = false
                                    searchError = getString(ReportsKeys.invalidQrCode, language)
                                }
                            },
                            onQrFailure = {
                                qrProcessing = false
                                searchError = it
                            },
                        )
                    }
            }
        }
    }
}

/**
 * Manual bill number entry content.
 */
@Composable
private fun ManualEntryContent(
    language: Language,
    billNumberInput: String,
    onBillNumberChange: (String) -> Unit,
    isSearching: Boolean,
    searchError: String?,
    onSearch: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosTextField(
            value = billNumberInput,
            onValueChange = onBillNumberChange,
            label = str(ReportsKeys.billNumber),
            placeholder = str(ReportsKeys.enterBillNumber),
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching,
        )

        Button( // raw Material 3 by design: button label changes between searching/search states; PosButton shows one fixed text
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = billNumberInput.isNotBlank() && !isSearching,
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(str(ReportsKeys.searching))
            } else {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(str(ReportsKeys.search))
            }
        }

        // Error message
        if (searchError != null) {
            Text(
                text = searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * QR code scanner content (Android only).
 */
@Composable
private fun QrScannerContent(
    language: Language,
    flashlightOn: Boolean,
    onToggleFlashlight: () -> Unit,
    qrProcessing: Boolean,
    searchError: String?,
    onQrScanned: (String) -> Unit,
    onQrFailure: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // QR Scanner viewfinder
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
        ) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                flashlightOn = flashlightOn,
                cameraLens = CameraLens.Back,
                openImagePicker = false,
                onCompletion = { scannedData -> onQrScanned(scannedData) },
                imagePickerHandler = { },
                onFailure = { errorMsg -> onQrFailure(errorMsg) },
            )
        }

        // Flashlight toggle
        PosFilledIconButton(
            icon = if (flashlightOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
            contentDescription = str(AccessibilityKeys.toggleFlashlight),
            onClick = onToggleFlashlight,
        )

        // Processing indicator
        if (qrProcessing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = str(ReportsKeys.searching),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Error message
        if (searchError != null) {
            Text(
                text = searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
