/**
 * Printer Location Card component.
 *
 * Displays a printer location (Kitchen, Bar, etc.) with inline printer setup information.
 * Non-expandable design - all information is visible at once.
 *
 * Shows:
 * - Location name
 * - Assigned printer details (Type: USB/Network, Name, IP:Port)
 * - Print Bill capability indicator
 * - Category mappings (if any)
 * - Edit/Delete actions
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.*

/**
 * Printer location card with inline printer setup display.
 *
 * @param location Printer location data with embedded printer setup
 * @param language Current app language for localization
 * @param onClick Callback when card is tapped (for viewing print jobs on phone)
 * @param onEdit Callback when edit button is clicked
 * @param onActivate Callback when activating an inactive location
 * @param onDeactivate Callback when deactivating an active location
 * @param modifier Optional modifier
 * @param selected marks the card whose print history is shown (wide layout)
 * @param phoneLayout stacks the header (name on its own line, badge and actions below) and uses 48dp buttons; default is the original single-row header.
 */
@Composable
fun PrinterLocationCard(
    location: PrinterDto.PrinterLocationResponseDto,
    language: Language,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onTestPrint: () -> Unit,
    isTestPrinting: Boolean = false,
    modifier: Modifier = Modifier,
    phoneLayout: Boolean = false,
    selected: Boolean = false,
) {
    var showDeactivateConfirmation by remember { mutableStateOf(false) }
    var showActivateConfirmation by remember { mutableStateOf(false) }
    val onToggleActive: (Boolean) -> Unit = { isActive ->
        // Both directions confirm first.
        if (isActive) showActivateConfirmation = true else showDeactivateConfirmation = true
    }

    PosCard(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header: Location name + active status + actions
                if (phoneLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            // Wraps instead of shrinking: PosAutoSizeText would squeeze the name toward 5sp.
                            Text(
                                text = location.printerLocation,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LocationStatusBadge(isActive = location.isActive)
                            LocationActions(location, language, isTestPrinting, 48.dp, onTestPrint, onEdit, onToggleActive)
                        }
                    }
                } else {
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
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = location.printerLocation,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            LocationStatusBadge(isActive = location.isActive)
                        }
                        LocationActions(location, language, isTestPrinting, 40.dp, onTestPrint, onEdit, onToggleActive)
                    }
                }

                if (location.isActive) {
                    HorizontalDivider()

                    // Printer setup information (inline)
                    location.printerSetup?.let { printerSetup ->
                        PrinterSetupInfo(
                            printerSetup = printerSetup,
                            language = language,
                        )
                    } ?: run {
                        // No printer assigned
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = str(PrinterSettingsKeys.validationPrinterRequired),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // Print Bill capability
                    if (location.printBill) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = str(PrinterSettingsKeys.printBill),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    // Category mappings
                    if (location.categoryIds.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Category,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp).padding(top = 2.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = str(PrinterSettingsKeys.categories),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = "${location.categoryIds.size} ${
                                        str(PrinterSettingsKeys.categories).lowercase()
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } // Column
        } // body
    }

    // Deactivate confirmation dialog
    if (showDeactivateConfirmation) {
        PosDialog(
            onDismiss = { showDeactivateConfirmation = false },
            title = str(PrinterSettingsKeys.deactivateConfirmationTitle),
            titleIcon = Icons.Default.Warning,
            footer = {
                PosTextButton(
                    text = getString(CommonKeys.cancel, language),
                    onClick = { showDeactivateConfirmation = false },
                )
                PosDangerButton(
                    text = str(PrinterSettingsKeys.deactivate),
                    onClick = {
                        showDeactivateConfirmation = false
                        onDeactivate()
                    },
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(PrinterSettingsKeys.deactivateLocationConfirmation))
                if (location.categoryIds.isNotEmpty()) {
                    Text(
                        text = str(PrinterSettingsKeys.deactivateLocationWarning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Activate confirmation dialog
    if (showActivateConfirmation) {
        PosDialog(
            onDismiss = { showActivateConfirmation = false },
            title = str(PrinterSettingsKeys.activateConfirmationTitle),
            titleIcon = Icons.Default.CheckCircle,
            footer = {
                PosTextButton(
                    text = getString(CommonKeys.cancel, language),
                    onClick = { showActivateConfirmation = false },
                )
                PosButton(
                    text = str(PrinterSettingsKeys.activate),
                    onClick = {
                        showActivateConfirmation = false
                        onActivate()
                    },
                )
            },
        ) {
            Text(str(PrinterSettingsKeys.activateLocationConfirmation))
        }
    }
}

/** Active / inactive pill next to the location name. */
@Composable
private fun LocationStatusBadge(isActive: Boolean) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val onColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = onColor,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = if (isActive) str(SettingsKeys.active) else str(SettingsKeys.inactive),
                style = MaterialTheme.typography.labelSmall,
                color = onColor,
            )
        }
    }
}

/** Test print (active locations with a printer), edit (active locations) and the activate/deactivate switch. */
@Composable
private fun LocationActions(
    location: PrinterDto.PrinterLocationResponseDto,
    language: Language,
    isTestPrinting: Boolean,
    buttonSize: Dp,
    onTestPrint: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (location.isActive && location.printerSetup != null) {
            if (isTestPrinting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                PosIconButton(
                    icon = Icons.Default.Print,
                    contentDescription = getString(PrinterSettingsKeys.testPrint, language),
                    onClick = onTestPrint,
                    tint = MaterialTheme.colorScheme.tertiary,
                    size = buttonSize,
                )
            }
        }
        if (location.isActive) {
            PosIconButton(
                icon = Icons.Default.Edit,
                contentDescription = getString(CommonKeys.edit, language),
                onClick = onEdit,
                tint = MaterialTheme.colorScheme.primary,
                size = buttonSize,
            )
        }
        Switch(
            checked = location.isActive,
            onCheckedChange = onToggleActive,
            colors =
                SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colorScheme.error,
                    uncheckedTrackColor = MaterialTheme.colorScheme.errorContainer,
                ),
        )
    }
}

/**
 * Printer setup information display (inline within location card).
 *
 * Shows printer type, name, and connection details.
 */
@Composable
private fun PrinterSetupInfo(
    printerSetup: PrinterDto.PrinterSetupResponseDto,
    language: Language,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Printer type badge
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Print,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            Surface(
                color =
                    when (printerSetup.printerType) {
                        "USB" -> MaterialTheme.colorScheme.primaryContainer
                        "NETWORK" -> MaterialTheme.colorScheme.tertiaryContainer
                        "IPP" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text =
                        when (printerSetup.printerType) {
                            "USB" -> str(PrinterSettingsKeys.printerTypeUsb)
                            "NETWORK" -> str(PrinterSettingsKeys.printerTypeNetwork)
                            "IPP" -> str(PrinterSettingsKeys.printerTypeIpp)
                            else -> printerSetup.printerType
                        },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color =
                        when (printerSetup.printerType) {
                            "USB" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "NETWORK" -> MaterialTheme.colorScheme.onTertiaryContainer
                            "IPP" -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            // IPP document format badge
            if (printerSetup.printerType == "IPP" && printerSetup.ippDocumentFormat != null) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text =
                            when (printerSetup.ippDocumentFormat) {
                                "ESC_POS" -> "ESC/POS"
                                "PDF" -> "PDF"
                                else -> printerSetup.ippDocumentFormat ?: ""
                            },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // Default printer badge
            if (printerSetup.defaultPrinter) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = str(PrinterSettingsKeys.defaultPrinter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        // Printer name
        printerSetup.printerName?.let { name ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(PrinterSettingsKeys.printerName) + ":",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PosAutoSizeText(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Network printer details (IP:Port)
        if (printerSetup.printerType == "NETWORK") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.NetworkCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "${printerSetup.printerIp}:${printerSetup.printerPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // IPP printer details (URI)
        if (printerSetup.printerType == "IPP" && !printerSetup.printerIp.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = printerSetup.printerIp ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
