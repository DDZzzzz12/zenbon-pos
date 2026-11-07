package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrintDisabled
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.PosSpacing

/**
 * PosDialog-based add/edit printer location dialog.
 *
 * Replaces the old ModalBottomSheet-based AddPrinterLocationDialog with the
 * PosDialog template for consistent design language. Integrates printer discovery
 * inline rather than via a separate dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosAddPrinterDialog(
    language: Language,
    availableCategories: List<ProductCategoryDto.ProductCategoryResponseDto>,
    editingLocation: PrinterDto.PrinterLocationResponseDto? = null,
    discoveredPrinters: List<PrinterDto.DiscoveredPrinterDto> = emptyList(),
    isDiscovering: Boolean = false,
    onDiscoverPrinters: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (PrinterDto.CreatePrinterLocationRequestDto) -> Unit,
    onUpdate: (PrinterDto.UpdatePrinterLocationRequestDto) -> Unit,
    phoneLayout: Boolean = false,
) {
    // Printer Setup State
    var printerType by remember { mutableStateOf(editingLocation?.printerSetup?.printerType ?: "USB") }
    var printerName by remember { mutableStateOf(editingLocation?.printerSetup?.printerName ?: "") }
    var printerIp by remember { mutableStateOf(editingLocation?.printerSetup?.printerIp ?: "") }
    var printerPort by remember { mutableStateOf(editingLocation?.printerSetup?.printerPort?.toString() ?: "9100") }
    var defaultPrinter by remember { mutableStateOf(editingLocation?.printerSetup?.defaultPrinter ?: false) }
    var ippDocumentFormat by remember { mutableStateOf(editingLocation?.printerSetup?.ippDocumentFormat ?: "ESC_POS") }

    // Printer Location State
    var locationName by remember { mutableStateOf(editingLocation?.printerLocation ?: "") }
    var printBill by remember { mutableStateOf(editingLocation?.printBill ?: false) }
    var beepMode by remember { mutableStateOf(editingLocation?.beepMode ?: BeepMode.DISABLED) }
    var selectedCategories by remember { mutableStateOf(editingLocation?.categoryIds?.toSet() ?: emptySet()) }

    // Validation State
    var showValidationErrors by remember { mutableStateOf(false) }

    // Discovery panel visibility
    var showDiscoveryPanel by remember { mutableStateOf(false) }

    // Unsaved edits: any field differs from the seed it was initialised with. Inert in the till
    // (no guard provider above), so there Cancel / scrim still close at once.
    val setupSeed = editingLocation?.printerSetup
    val isDirty =
        printerType != (setupSeed?.printerType ?: "USB") ||
            printerName != (setupSeed?.printerName ?: "") ||
            printerIp != (setupSeed?.printerIp ?: "") ||
            printerPort != (setupSeed?.printerPort?.toString() ?: "9100") ||
            defaultPrinter != (setupSeed?.defaultPrinter ?: false) ||
            ippDocumentFormat != (setupSeed?.ippDocumentFormat ?: "ESC_POS") ||
            locationName != (editingLocation?.printerLocation ?: "") ||
            printBill != (editingLocation?.printBill ?: false) ||
            beepMode != (editingLocation?.beepMode ?: BeepMode.DISABLED) ||
            selectedCategories != editingLocation?.categoryIds?.toSet().orEmpty()
    val close = rememberGuardedDismiss(dirty = isDirty, onClose = onDismiss)

    // Validation Logic
    val printerNameError =
        when {
            showValidationErrors && printerName.isBlank() -> str(PrinterSettingsKeys.validationPrinterNameRequired)
            else -> null
        }

    val printerIpError =
        when (printerType) {
            "NETWORK" ->
                if (showValidationErrors && printerIp.isBlank()) {
                    str(PrinterSettingsKeys.validationIpRequired)
                } else if (printerIp.isNotBlank() && !isValidIpAddress(printerIp)) {
                    str(PrinterSettingsKeys.validationInvalidIp)
                } else {
                    null
                }
            "IPP" ->
                if (showValidationErrors && printerIp.isBlank()) {
                    str(PrinterSettingsKeys.validationIppUriRequired)
                } else {
                    null
                }
            else -> null
        }

    val printerPortError =
        when {
            printerType == "NETWORK" && showValidationErrors && printerPort.isBlank() ->
                str(PrinterSettingsKeys.validationPortRequired)
            printerType == "NETWORK" && printerPort.isNotBlank() && printerPort.toIntOrNull() !in 1..65535 ->
                str(PrinterSettingsKeys.validationInvalidPort)
            else -> null
        }

    val locationNameError =
        when {
            showValidationErrors && locationName.isBlank() ->
                str(PrinterSettingsKeys.validationLocationNameRequired)
            else -> null
        }

    val ippDocumentFormatError =
        when {
            printerType == "IPP" && showValidationErrors && ippDocumentFormat.isBlank() ->
                str(PrinterSettingsKeys.validationDocumentFormatRequired)
            else -> null
        }

    val isFormValid =
        printerName.isNotBlank() &&
            locationName.isNotBlank() &&
            when (printerType) {
                "USB" -> true
                "NETWORK" ->
                    printerIp.isNotBlank() &&
                        isValidIpAddress(printerIp) &&
                        printerPort.toIntOrNull() in 1..65535
                "IPP" -> printerIp.isNotBlank() && ippDocumentFormat.isNotBlank()
                else -> false
            }

    PosDialog(
        onDismiss = close,
        title =
            if (editingLocation == null) {
                str(PrinterSettingsKeys.addLocation)
            } else {
                str(PrinterSettingsKeys.editLocation)
            },
        titleIcon = Icons.Default.Print,
        maxWidth = 520.dp,
        footer = {
            PosSecondaryButton(
                text = getString(CommonKeys.cancel, language),
                onClick = close,
            )
            PosButton(
                text = getString(CommonKeys.save, language),
                onClick = {
                    if (isFormValid) {
                        if (editingLocation != null) {
                            val printerSetupUpdate =
                                PrinterDto.UpdatePrinterSetupRequestDto(
                                    printerType = printerType,
                                    printerName = printerName.trim(),
                                    printerIp =
                                        when (printerType) {
                                            "NETWORK" -> printerIp.ifBlank { null }
                                            "IPP" -> printerIp.ifBlank { null }
                                            else -> null
                                        },
                                    printerPort = if (printerType == "NETWORK") printerPort.toIntOrNull() else null,
                                    defaultPrinter = defaultPrinter,
                                    ippDocumentFormat = if (printerType == "IPP") ippDocumentFormat else null,
                                )
                            val updateRequest =
                                PrinterDto.UpdatePrinterLocationRequestDto(
                                    printerLocation = locationName.trim(),
                                    printBill = printBill,
                                    beepMode = beepMode,
                                    printerSetup = printerSetupUpdate,
                                    categoryIds = selectedCategories.toList(),
                                )
                            onUpdate(updateRequest)
                        } else {
                            val printerSetup =
                                PrinterDto.CreatePrinterSetupRequestDto(
                                    printerType = printerType,
                                    printerName = printerName.trim(),
                                    printerIp =
                                        when (printerType) {
                                            "NETWORK" -> printerIp.ifBlank { null }
                                            "IPP" -> printerIp.ifBlank { null }
                                            else -> null
                                        },
                                    printerPort = if (printerType == "NETWORK") printerPort.toIntOrNull() else null,
                                    defaultPrinter = defaultPrinter,
                                    ippDocumentFormat = if (printerType == "IPP") ippDocumentFormat else null,
                                )
                            val createRequest =
                                PrinterDto.CreatePrinterLocationRequestDto(
                                    printerLocation = locationName.trim(),
                                    printBill = printBill,
                                    beepMode = beepMode,
                                    printerSetup = printerSetup,
                                    categoryIds = selectedCategories.toList(),
                                )
                            onConfirm(createRequest)
                        }
                    } else {
                        showValidationErrors = true
                    }
                },
                enabled = isFormValid || !showValidationErrors,
            )
        },
    ) {
        // SECTION 1: Printer Setup
        Text(
            text = str(PrinterSettingsKeys.printerSetupTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider()

        // Printer Type Selection
        Text(
            text = str(PrinterSettingsKeys.printerType),
            style = MaterialTheme.typography.labelLarge,
        )

        Column(Modifier.selectableGroup()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (printerType == "USB"),
                            onClick = { printerType = "USB" },
                            role = Role.RadioButton,
                        ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = (printerType == "USB"), onClick = null)
                Icon(imageVector = Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(str(PrinterSettingsKeys.printerTypeUsb))
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (printerType == "NETWORK"),
                            onClick = { printerType = "NETWORK" },
                            role = Role.RadioButton,
                        ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = (printerType == "NETWORK"), onClick = null)
                Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(str(PrinterSettingsKeys.printerTypeNetwork))
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (printerType == "IPP"),
                            onClick = { printerType = "IPP" },
                            role = Role.RadioButton,
                        ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = (printerType == "IPP"), onClick = null)
                Icon(imageVector = Icons.Default.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(str(PrinterSettingsKeys.printerTypeIpp))
            }
        }

        // Printer Name Field
        PosTextField(
            value = printerName,
            onValueChange = { printerName = it },
            label = str(PrinterSettingsKeys.printerName),
            leadingIcon = Icons.Default.Print,
            isError = printerNameError != null,
            errorMessage = printerNameError,
            modifier = Modifier.fillMaxWidth(),
        )

        // Network Printer Fields (conditional)
        if (printerType == "NETWORK") {
            PosTextField(
                value = printerIp,
                onValueChange = { printerIp = it },
                label = str(PrinterSettingsKeys.ipAddress),
                leadingIcon = Icons.Default.Router,
                isError = printerIpError != null,
                errorMessage = printerIpError,
                modifier = Modifier.fillMaxWidth(),
            )

            PosTextField(
                value = printerPort,
                onValueChange = { printerPort = it },
                label = str(PrinterSettingsKeys.port),
                leadingIcon = Icons.Default.Cable,
                isError = printerPortError != null,
                errorMessage = printerPortError,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // IPP Printer Fields (conditional)
        if (printerType == "IPP") {
            // IPP URI field with discover button
            PosTextField(
                value = printerIp,
                onValueChange = { printerIp = it },
                label = str(PrinterSettingsKeys.ippUri),
                leadingIcon = Icons.Default.Link,
                trailingIcon = {
                    PosIconButton(
                        icon = Icons.Default.Search,
                        contentDescription = str(PrinterSettingsKeys.discoverPrinters),
                        onClick = {
                            showDiscoveryPanel = true
                            onDiscoverPrinters()
                        },
                        size = if (phoneLayout) 48.dp else 40.dp,
                    )
                },
                isError = printerIpError != null,
                errorMessage = printerIpError,
                modifier = Modifier.fillMaxWidth(),
            )

            // Discover Printers button
            PosSecondaryButton(
                text = str(PrinterSettingsKeys.discoverPrinters),
                icon = Icons.Default.Search,
                onClick = {
                    showDiscoveryPanel = true
                    onDiscoverPrinters()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Integrated discovery panel (shown inline)
            if (showDiscoveryPanel) {
                DiscoveryPanel(
                    discoveredPrinters = discoveredPrinters,
                    isDiscovering = isDiscovering,
                    onRefresh = onDiscoverPrinters,
                    onSelectPrinter = { discovered ->
                        printerName = discovered.name
                        printerIp = discovered.uri
                        showDiscoveryPanel = false
                    },
                    onDismiss = { showDiscoveryPanel = false },
                )
            }

            // Document Format Selection
            Text(
                text = str(PrinterSettingsKeys.documentFormat),
                style = MaterialTheme.typography.labelLarge,
            )

            Column(Modifier.selectableGroup()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (ippDocumentFormat == "ESC_POS"),
                                onClick = { ippDocumentFormat = "ESC_POS" },
                                role = Role.RadioButton,
                            ).padding(vertical = PosSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = (ippDocumentFormat == "ESC_POS"), onClick = null)
                    Text(str(PrinterSettingsKeys.documentFormatEscPos))
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (ippDocumentFormat == "PDF"),
                                onClick = { ippDocumentFormat = "PDF" },
                                role = Role.RadioButton,
                            ).padding(vertical = PosSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = (ippDocumentFormat == "PDF"), onClick = null)
                    Text(str(PrinterSettingsKeys.documentFormatPdf))
                }
            }

            if (ippDocumentFormatError != null) {
                Text(
                    text = ippDocumentFormatError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Default Printer Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Star, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text = str(PrinterSettingsKeys.defaultPrinter), style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = defaultPrinter, onCheckedChange = { defaultPrinter = it })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // SECTION 2: Printer Location
        Text(
            text = str(PrinterSettingsKeys.printerLocationTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider()

        // Location Name Field
        PosTextField(
            value = locationName,
            onValueChange = { locationName = it },
            label = str(PrinterSettingsKeys.locationName),
            leadingIcon = Icons.Default.LocationOn,
            isError = locationNameError != null,
            errorMessage = locationNameError,
            modifier = Modifier.fillMaxWidth(),
            imeAction = ImeAction.Done,
        )

        // Print Bill Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(20.dp))
                Column {
                    Text(text = str(PrinterSettingsKeys.printBill), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = str(PrinterSettingsKeys.printBillDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = printBill, onCheckedChange = { printBill = it })
        }

        // Beep Alert Mode
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = str(PrinterSettingsKeys.beepMode),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            PosSegmentedButtonRow(
                options = listOf(BeepMode.DISABLED, BeepMode.SHORT, BeepMode.LONG),
                selectedOption = beepMode,
                onOptionSelected = { beepMode = it },
            ) { mode ->
                when (mode) {
                    BeepMode.DISABLED -> str(PrinterSettingsKeys.beepModeOff)
                    BeepMode.SHORT -> str(PrinterSettingsKeys.beepModeShort)
                    BeepMode.LONG -> str(PrinterSettingsKeys.beepModeLong)
                }
            }
        }

        // Category Selection
        PosCard(
            modifier = Modifier.fillMaxWidth(),
            variant = PosCardVariant.Plain,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            body {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header with Select All/Deselect All
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(PrinterSettingsKeys.categories),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (availableCategories.isNotEmpty()) {
                                Text(
                                    text = str(PrinterSettingsKeys.categoriesSelected, selectedCategories.size.toString(), availableCategories.size.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }

                        if (availableCategories.isNotEmpty()) {
                            val allSelected = selectedCategories.size == availableCategories.size
                            // raw Material 3 by design: TextButton with conditional icon and conditional text, no PosTextButton variant supports both
                            TextButton(
                                onClick = {
                                    selectedCategories =
                                        if (allSelected) {
                                            emptySet()
                                        } else {
                                            availableCategories.map { it.id.toLong() }.toSet()
                                        }
                                },
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Default.Clear else Icons.Default.SelectAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text =
                                        if (allSelected) {
                                            str(PrinterSettingsKeys.deselectAll)
                                        } else {
                                            str(PrinterSettingsKeys.selectAll)
                                        },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // Category chips or empty state
                    if (availableCategories.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = str(PrinterSettingsKeys.noCategoriesAvailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val toggleCategory: (Long) -> Unit = { id ->
                            selectedCategories =
                                if (selectedCategories.contains(id)) selectedCategories - id else selectedCategories + id
                        }
                        if (phoneLayout) {
                            // No second scroll container inside the dialog's own scroll, and the
                            // chips take the width their name needs.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                availableCategories.forEach { category ->
                                    val categoryId: Long = category.id.toLong()
                                    CategoryFilterChip(
                                        category = category,
                                        selected = selectedCategories.contains(categoryId),
                                        onToggle = { toggleCategory(categoryId) },
                                    )
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(availableCategories) { category ->
                                    val categoryId: Long = category.id.toLong()
                                    CategoryFilterChip(
                                        category = category,
                                        selected = selectedCategories.contains(categoryId),
                                        onToggle = { toggleCategory(categoryId) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterChip(
    category: ProductCategoryDto.ProductCategoryResponseDto,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        },
        leadingIcon =
            if (selected) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                null
            },
        modifier = modifier,
    )
}

/**
 * Inline discovery panel shown within the dialog when IPP is selected.
 *
 * Displays discovered printers in a list, with loading and empty states.
 * Selecting a printer auto-fills the URI and name fields.
 */
@Composable
private fun DiscoveryPanel(
    discoveredPrinters: List<PrinterDto.DiscoveredPrinterDto>,
    isDiscovering: Boolean,
    onRefresh: () -> Unit,
    onSelectPrinter: (PrinterDto.DiscoveredPrinterDto) -> Unit,
    onDismiss: () -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(PrinterSettingsKeys.selectDiscoveredPrinter),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    PosTextButton(
                        text = str(CommonKeys.cancel),
                        onClick = onDismiss,
                    )
                }

                HorizontalDivider()

                if (isDiscovering) {
                    // Loading state
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(
                            text = str(PrinterSettingsKeys.discoveringPrinters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (discoveredPrinters.isEmpty()) {
                    // Empty state
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PrintDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = str(PrinterSettingsKeys.noDiscoveredPrinters),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = str(PrinterSettingsKeys.noDiscoveredPrintersDescription),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Printer list
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(discoveredPrinters) { printer ->
                            DiscoveredPrinterItem(
                                printer = printer,
                                onClick = { onSelectPrinter(printer) },
                            )
                        }
                    }
                }

                // Refresh button
                PosSecondaryButton(
                    text = str(PrinterSettingsKeys.refresh),
                    icon = Icons.Default.Refresh,
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDiscovering,
                )
            }
        }
    }
}

/**
 * Individual discovered printer item in the inline discovery panel.
 */
@Composable
private fun DiscoveredPrinterItem(
    printer: PrinterDto.DiscoveredPrinterDto,
    onClick: () -> Unit,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        onClick = onClick,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint =
                        when (printer.state.lowercase()) {
                            "idle" -> MaterialTheme.colorScheme.primary
                            "processing" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                    modifier = Modifier.size(28.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PosAutoSizeText(
                        text = printer.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    printer.makeAndModel?.let { model ->
                        PosAutoSizeText(
                            text = model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = printer.uri,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    color =
                        when (printer.state.lowercase()) {
                            "idle" -> MaterialTheme.colorScheme.primaryContainer
                            "processing" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text =
                            when (printer.state.lowercase()) {
                                "idle" -> str(PrinterSettingsKeys.printerStateIdle)
                                "processing" -> str(PrinterSettingsKeys.printerStateProcessing)
                                "stopped" -> str(PrinterSettingsKeys.printerStateStopped)
                                else -> printer.state
                            },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color =
                            when (printer.state.lowercase()) {
                                "idle" -> MaterialTheme.colorScheme.onPrimaryContainer
                                "processing" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            },
                    )
                }
            }
        }
    }
}

/**
 * Validates IP address format (IPv4).
 */
private fun isValidIpAddress(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val num = part.toIntOrNull()
        num != null && num in 0..255
    }
}
