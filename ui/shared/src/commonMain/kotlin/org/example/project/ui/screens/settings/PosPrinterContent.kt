package org.example.project.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.state.PrinterSettingsState
import org.example.project.features.settings.presentation.viewmodel.PrinterSettingsViewModel
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.screens.settings.components.PosAddPrinterDialog
import org.example.project.ui.screens.settings.components.PrintJobSidePanel
import org.example.project.ui.screens.settings.components.PrinterLocationCard
import org.example.project.ui.screens.settings.components.SettingsContentHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param canEditPrintSettings whether the viewer holds `CONFIG_WRITE`, which `ConfigRules` puts on
 *   `PUT /api/v1/config/{shopId}/print-settings`. Without it the master switch shows its state but
 *   cannot be flipped (admin20 FAIL-C20: a cashier could flip it and only the server's 403 stopped it).
 *   Printer *locations* stay editable — the server gates them separately.
 * @param compactLayout `null` (the default, and what composeApp passes) lets the window size class
 *   choose between the phone layout and the two-pane layout, exactly as before. adminApp passes the
 *   verdict of the width the section really has: `true` selects the phone layout **and** the phone
 *   styling (stacked location-card header, 48dp controls, wrapping chips, FAB clearance, opaque
 *   history sheet); `false` forces the two-pane layout.
 */
@Composable
fun PosPrinterContent(
    language: Language,
    isCompact: Boolean,
    canEditPrintSettings: Boolean,
    viewModel: PrinterSettingsViewModel = koinViewModel(),
    compactLayout: Boolean? = null,
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    // State for managing print job panel visibility on phone
    var showPrintJobPanel by remember { mutableStateOf(false) }
    var selectedLocationId by remember { mutableStateOf<Long?>(null) }

    val isCompactScreen = compactLayout ?: windowSize.isCompact()
    val phone = compactLayout == true

    // Trigger initial data loading (handles ViewModel cache/reuse across navigation)
    LaunchedEffect(Unit) {
        viewModel.loadPrintSettings()
        viewModel.loadPrinterLocations()
        viewModel.loadProductCategories()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add header on desktop only
        if (!isCompact) {
            SettingsContentHeader(
                icon = Icons.Default.Print,
                title = str(SettingsKeys.printerTab),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (isCompactScreen) {
                // Phone layout: Full width content + FAB
                CompactLayout(
                    uiState = uiState,
                    isCompactScreen = true,
                    phone = phone,
                    language = language,
                    canEditPrintSettings = canEditPrintSettings,
                    viewModel = viewModel,
                    onLocationClick = { locationId ->
                        selectedLocationId = locationId
                        showPrintJobPanel = true
                    },
                    onAddPrinterLocation = { viewModel.showAddLocationDialog() },
                )

                // Floating Action Button for all print jobs
                FloatingActionButton(
                    onClick = {
                        selectedLocationId = null
                        showPrintJobPanel = true
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                ) {
                    Icon(Icons.Default.History, contentDescription = str(PrinterSettingsKeys.printJobHistoryTitle))
                }
            } else {
                // Tablet/Desktop layout: Two-pane with side panel
                ExpandedLayout(
                    uiState = uiState,
                    language = language,
                    canEditPrintSettings = canEditPrintSettings,
                    viewModel = viewModel,
                    onAddPrinterLocation = { viewModel.showAddLocationDialog() },
                )
            }
        }
    }

    // Handle success/error messages via global notification system
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            overlay.showSuccess(
                title = getString(PrinterSettingsKeys.printerLocationTitle, language),
                // saveSuccess is raised by every save, the master switch included: "Drucker
                // erfolgreich hinzugefügt" after flipping that switch was wrong (admin20 NOTE-g).
                message = getString(PrinterSettingsKeys.successSaved, language),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearSaveSuccess()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { err ->
            overlay.showWarning(
                title = getString(PrinterSettingsKeys.printerLocationTitle, language),
                message = getString(err.messageKey, language, *err.messageArgs.toTypedArray()),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.testPrintSuccess) {
        if (uiState.testPrintSuccess) {
            overlay.showSuccess(
                title = getString(PrinterSettingsKeys.testPrint, language),
                message = getString(PrinterSettingsKeys.testPrintSuccess, language),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearTestPrintSuccess()
        }
    }

    // Unified Printer Location Dialog
    if (uiState.printerLocationSection.showAddDialog) {
        PosAddPrinterDialog(
            language = language,
            availableCategories = uiState.printerLocationSection.availableCategories,
            editingLocation = uiState.printerLocationSection.editingLocation,
            discoveredPrinters = uiState.printerLocationSection.discoveredPrinters,
            isDiscovering = uiState.printerLocationSection.isDiscovering,
            onDiscoverPrinters = { viewModel.showDiscoveryDialog() },
            onDismiss = { viewModel.dismissLocationDialog() },
            onConfirm = { request ->
                viewModel.addPrinterLocation(request)
            },
            onUpdate = { request ->
                val editingLocation = uiState.printerLocationSection.editingLocation!!
                viewModel.updatePrinterLocation(editingLocation.id, request)
            },
            phoneLayout = phone,
        )
    }

    // Print job panel for phone (modal)
    if (showPrintJobPanel && isCompactScreen) {
        // Scope the history to the location the sheet was opened for (null = all) and load it
        LaunchedEffect(showPrintJobPanel, selectedLocationId) {
            viewModel.selectHistoryLocation(selectedLocationId)
        }

        PrintJobPanelModal(
            language = language,
            printerLocationId = selectedLocationId,
            printerLocationName =
                uiState.printerLocationSection.locations
                    .firstOrNull { it.id == selectedLocationId }
                    ?.printerLocation,
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = {
                showPrintJobPanel = false
                viewModel.resetHistoryLocation()
            },
            phone = phone,
        )
    }
}

/**
 * Compact layout for phone screens.
 *
 * Single column showing printer location cards.
 * Tap on card to view print jobs for that location.
 * FAB shows all print jobs.
 */
@Composable
private fun CompactLayout(
    uiState: PrinterSettingsState,
    language: Language,
    canEditPrintSettings: Boolean,
    viewModel: PrinterSettingsViewModel,
    onLocationClick: (Long) -> Unit,
    onAddPrinterLocation: () -> Unit,
    isCompactScreen: Boolean = false,
    phone: Boolean = false,
    selectedLocationId: Long? = null,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = if (phone) 88.dp else 0.dp),
    ) {
        // Print Settings Toggle
        item {
            PrintSettingsToggleCard(
                enabled = uiState.printSettingsEnabled,
                isLoading = uiState.isLoadingPrintSettings,
                editable = canEditPrintSettings,
                onToggle = { enabled ->
                    if (canEditPrintSettings) viewModel.updatePrintSettings(enabled)
                },
                language = language,
            )
        }

        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(PrinterSettingsKeys.printerLocationTitle),
                    style = MaterialTheme.typography.titleLarge,
                )
                PosIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = str(PrinterSettingsKeys.addLocation),
                    onClick = onAddPrinterLocation,
                    size = if (phone) 48.dp else 40.dp,
                )
            }
        }

        // Only show printer locations if print settings are enabled
        if (uiState.printSettingsEnabled) {
            // Loading state
            if (uiState.printerLocationSection.isLoadingLocations) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Printer location cards
            items(uiState.printerLocationSection.locations) { location ->
                PrinterLocationCard(
                    location = location,
                    language = language,
                    onClick = { onLocationClick(location.id) },
                    onEdit = { viewModel.showEditLocationDialog(location) },
                    onActivate = { viewModel.activatePrinterLocation(location.id) },
                    onDeactivate = { viewModel.deletePrinterLocation(location.id) },
                    onTestPrint = { viewModel.testPrint(location.id) },
                    isTestPrinting = uiState.testPrintingLocationId == location.id,
                    phoneLayout = phone,
                    selected = selectedLocationId == location.id,
                )
            }

            // Empty state
            if (uiState.printerLocationSection.locations.isEmpty() && !uiState.printerLocationSection.isLoadingLocations) {
                item {
                    EmptyState(
                        title = str(PrinterSettingsKeys.noLocations),
                        description = str(PrinterSettingsKeys.noLocationsDescription),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
        }
    }
}

/**
 * Expanded layout for tablet/desktop screens.
 *
 * Two-pane layout:
 * - Left: Printer location cards
 * - Right: Print job history panel with date filtering
 */
@Composable
private fun ExpandedLayout(
    uiState: PrinterSettingsState,
    language: Language,
    canEditPrintSettings: Boolean,
    viewModel: PrinterSettingsViewModel,
    onAddPrinterLocation: () -> Unit,
) {
    val selectedLocationId = uiState.printJobHistorySection.locationId
    val selectedLocation = uiState.printerLocationSection.locations.find { it.id == selectedLocationId }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: Printer locations (60% width)
        Box(
            modifier =
                Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
        ) {
            CompactLayout(
                uiState = uiState,
                language = language,
                canEditPrintSettings = canEditPrintSettings,
                viewModel = viewModel,
                onLocationClick = { id ->
                    // Tapping the selected card again clears the history filter
                    viewModel.selectHistoryLocation(if (id == selectedLocationId) null else id)
                },
                onAddPrinterLocation = onAddPrinterLocation,
                selectedLocationId = selectedLocationId,
            )
        }

        // Divider
        VerticalDivider()

        // Right pane: Print job history (40% width)
        Box(
            modifier =
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
        ) {
            // Load print job history when the panel first appears
            LaunchedEffect(Unit) {
                viewModel.loadPrintJobHistory()
            }

            PrintJobSidePanel(
                language = language,
                printJobs = uiState.printJobHistorySection.history,
                isLoading = uiState.printJobHistorySection.isLoadingHistory,
                statusFilter = uiState.printJobHistorySection.statusFilter,
                printTypeFilter = uiState.printJobHistorySection.printTypeFilter,
                selectedDateRange = uiState.printJobHistorySection.selectedDateRange,
                onStatusFilterChange = { viewModel.updateStatusFilter(it) },
                onPrintTypeFilterChange = { viewModel.updatePrintTypeFilter(it) },
                onDateRangeChange = { viewModel.updateDateRange(it) },
                onRefresh = { viewModel.loadPrintJobHistory() },
                locationId = selectedLocationId,
                locationName = selectedLocation?.printerLocation,
                onClearLocation = { viewModel.selectHistoryLocation(null) },
            )
        }
    }
}

/**
 * Empty state component for when no printer locations are configured.
 */
@Composable
private fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * Print settings toggle card component.
 *
 * Displays a card with a switch to enable/disable printer configuration.
 * When disabled, printer locations and configurations are hidden.
 */
@Composable
private fun PrintSettingsToggleCard(
    enabled: Boolean,
    isLoading: Boolean,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    language: Language,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        containerColor =
            if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(PrinterSettingsKeys.printSettingsTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = str(PrinterSettingsKeys.printSettingsDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        enabled = editable,
                    )
                }
            }
        }
    }
}

/**
 * Print job panel for phone screens.
 *
 * Slides in from the right side similar to the notification panel.
 */
@Composable
private fun PrintJobPanelModal(
    language: Language,
    printerLocationId: Long?,
    printerLocationName: String?,
    uiState: PrinterSettingsState,
    viewModel: PrinterSettingsViewModel,
    onDismiss: () -> Unit,
    phone: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Print job panel that slides in from right
        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(if (phone) Modifier.background(MaterialTheme.colorScheme.background) else Modifier),
            ) {
                // Header
                PrintJobPanelHeader(
                    printerLocationId = printerLocationId,
                    printerLocationName = printerLocationName,
                    language = language,
                    onClose = onDismiss,
                    phone = phone,
                )

                HorizontalDivider()

                // Content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PrintJobSidePanel(
                        language = language,
                        printJobs = uiState.printJobHistorySection.history,
                        isLoading = uiState.printJobHistorySection.isLoadingHistory,
                        statusFilter = uiState.printJobHistorySection.statusFilter,
                        printTypeFilter = uiState.printJobHistorySection.printTypeFilter,
                        selectedDateRange = uiState.printJobHistorySection.selectedDateRange,
                        onStatusFilterChange = { viewModel.updateStatusFilter(it) },
                        onPrintTypeFilterChange = { viewModel.updatePrintTypeFilter(it) },
                        onDateRangeChange = { viewModel.updateDateRange(it) },
                        onRefresh = { viewModel.loadPrintJobHistory() },
                        locationId = uiState.printJobHistorySection.locationId,
                        phoneLayout = phone,
                    )
                }
            }
        }
    }
}

/**
 * Header for the print job panel.
 */
@Composable
private fun PrintJobPanelHeader(
    printerLocationId: Long?,
    printerLocationName: String?,
    language: Language,
    onClose: () -> Unit,
    phone: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
            )
            Text(
                text =
                    if (printerLocationId != null) {
                        "${str(PrinterSettingsKeys.printJobHistoryTitle)} - ${printerLocationName ?: "#$printerLocationId"}"
                    } else {
                        str(PrinterSettingsKeys.printJobHistoryTitle)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
        }

        PosIconButton(
            icon = Icons.Default.Close,
            contentDescription = getString(CommonKeys.cancel, language),
            onClick = onClose,
            size = if (phone) 48.dp else 40.dp,
        )
    }
}
