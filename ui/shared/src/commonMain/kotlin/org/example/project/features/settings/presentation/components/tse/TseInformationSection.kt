/**
 * TSE information section component.
 *
 * Displays TSE device information including serial number, firmware,
 * signatures, capacity, and optionally health check and flash health
 * information with animated expand/collapse.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.tseInitializationStateLabel
import org.example.project.core.utils.enumStrings.tseLogTimeFormatLabel
import org.example.project.core.utils.formatEpochMillisShortDateTime
import org.example.project.core.utils.formatGrouped
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.LocalPosColors

/**
 * Displays TSE device information including serial number, firmware,
 * signatures, capacity, and optionally health check and flash health.
 *
 * @param language Current language for localization
 * @param tseInfo TSE information data
 * @param healthCheck Optional health check result data
 * @param flashHealth Optional flash health result data
 */
@Composable
fun TseInformationSection(
    language: Language,
    tseInfo: TseDto.TseInfoDto,
    status: TseDto.StatusInfoDto? = null,
    healthCheck: TseDto.HealthCheckResultDto? = null,
    flashHealth: TseDto.FlashHealthResultDto? = null,
    windowSize: WindowSizeClass,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSectionHeader(
                    title = str(TseSettingsKeys.tseInformationSection),
                    icon = Icons.Default.Info,
                )

                HorizontalDivider()

                // Info grid — stack vertically on compact
                if (windowSize.isCompact()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TseInfoRow(str(TseSettingsKeys.serialNumber), tseInfo.tseSerialNumberHex)
                        TseInfoRow(str(TseSettingsKeys.formFactor), tseInfo.formFactor)
                        TseInfoRow(str(TseSettingsKeys.initializationState), tseInitializationStateLabel(tseInfo.initializationState, language))
                        TseInfoRow(
                            str(TseSettingsKeys.tseGeneration),
                            str(
                                if (tseInfo.isTseV2) {
                                    TseSettingsKeys.tseGenerationV2
                                } else {
                                    TseSettingsKeys.tseGenerationV1
                                },
                            ),
                        )
                        if (tseInfo.isTseV2) {
                            // v2 only: v1 permits several concurrent sessions and never logs
                            // anyone out for inactivity, so neither row means anything there.
                            TseInfoRow(str(TseSettingsKeys.loggedInUser), tseInfo.loggedInUser)
                            if (tseInfo.loggedInUserAutoLogOutTimeout > 0) {
                                TseInfoRow(
                                    str(TseSettingsKeys.autoLogoutIn, tseInfo.loggedInUserAutoLogOutTimeout / 60),
                                    "",
                                )
                            }
                        }
                        TseInfoRow(str(TseSettingsKeys.firmwareVersion), tseInfo.softwareVersion.toString())
                        TseInfoRow(str(TseSettingsKeys.hardwareVersion), tseInfo.hardwareVersion.toString())
                        TseInfoRow(str(TseSettingsKeys.experationDate), (tseInfo.certificateExpirationDate * 1000).formatEpochMillisShortDateTime(language))
                        status?.sdkVersion?.let { TseInfoRow(str(TseSettingsKeys.sdkVersion), it) }
                        status?.logTimeFormat?.let { TseInfoRow(str(TseSettingsKeys.logTimeFormat), tseLogTimeFormatLabel(it, language)) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            TseInfoRow(str(TseSettingsKeys.serialNumber), tseInfo.tseSerialNumberHex)
                            TseInfoRow(str(TseSettingsKeys.formFactor), tseInfo.formFactor)
                            TseInfoRow(str(TseSettingsKeys.initializationState), tseInitializationStateLabel(tseInfo.initializationState, language))
                            TseInfoRow(
                                str(TseSettingsKeys.tseGeneration),
                                str(
                                    if (tseInfo.isTseV2) {
                                        TseSettingsKeys.tseGenerationV2
                                    } else {
                                        TseSettingsKeys.tseGenerationV1
                                    },
                                ),
                            )
                            if (tseInfo.isTseV2) {
                                // v2 only: v1 permits several concurrent sessions and never logs
                                // anyone out for inactivity, so neither row means anything there.
                                TseInfoRow(str(TseSettingsKeys.loggedInUser), tseInfo.loggedInUser)
                                if (tseInfo.loggedInUserAutoLogOutTimeout > 0) {
                                    TseInfoRow(
                                        str(TseSettingsKeys.autoLogoutIn, tseInfo.loggedInUserAutoLogOutTimeout / 60),
                                        "",
                                    )
                                }
                            }
                            status?.sdkVersion?.let { TseInfoRow(str(TseSettingsKeys.sdkVersion), it) }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            TseInfoRow(str(TseSettingsKeys.firmwareVersion), tseInfo.softwareVersion.toString())
                            TseInfoRow(str(TseSettingsKeys.hardwareVersion), tseInfo.hardwareVersion.toString())
                            TseInfoRow(str(TseSettingsKeys.experationDate), (tseInfo.certificateExpirationDate * 1000).formatEpochMillisShortDateTime(language))
                            status?.logTimeFormat?.let { TseInfoRow(str(TseSettingsKeys.logTimeFormat), tseLogTimeFormatLabel(it, language)) }
                        }
                    }
                }

                // Signatures progress
                Column {
                    Text(
                        text = str(TseSettingsKeys.signatures),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Guard the divide, as with capacity below: maxSignatures is 0 when the
                    // device has not reported one, which would render the bar as NaN.
                    val signatureProgress =
                        if (tseInfo.maxSignatures > 0) {
                            tseInfo.createdSignatures.toFloat() / tseInfo.maxSignatures.toFloat()
                        } else {
                            0f
                        }
                    LinearProgressIndicator(
                        progress = { signatureProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val created = tseInfo.createdSignatures.toLong().formatGrouped(language)
                    val max = tseInfo.maxSignatures.toLong().formatGrouped(language)
                    val left = tseInfo.remainingSignatures.toLong().formatGrouped(language)
                    Text(
                        text = "$created / $max ($left ${str(TseSettingsKeys.remaining)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (tseInfo.signaturesRunningLow) {
                        // The other thing that ends a device's life besides certificate
                        // expiry — worth the same notice, and the same lead time to order a
                        // replacement before it stops signing.
                        Text(
                            text =
                                str(
                                    TseSettingsKeys.signaturesRunningLowWarning,
                                    tseInfo.remainingSignatures,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Capacity
                Column {
                    Text(
                        text = str(SettingsKeys.capacity),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Guard the divide: capacity is 0 when the device has not reported one.
                    val capacityProgress =
                        if (tseInfo.capacity > 0) tseInfo.size.toFloat() / tseInfo.capacity.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { capacityProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${tseInfo.size.formatGrouped(language)} / ${tseInfo.capacity.formatGrouped(language)} ${str(TseSettingsKeys.bytesUnit)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TseInfoRow(
                        str(TseSettingsKeys.freeSpace),
                        "${tseInfo.freeBytes / 1024 / 1024} MB",
                    )
                    if (tseInfo.shouldExportSoon) {
                        // A TSE 2.0 stops accepting transactions with ~1 MB still free, so
                        // this fires long before the device looks full.
                        Text(
                            text = str(TseSettingsKeys.exportSoonWarning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Health Check Section (animated)
                AnimatedVisibility(
                    visible = healthCheck != null,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    healthCheck?.let { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()

                            // Health status header
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (check.healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (check.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text =
                                        if (check.healthy) {
                                            str(TseSettingsKeys.healthy)
                                        } else {
                                            str(TseSettingsKeys.unhealthy)
                                        },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (check.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TseCheckItem(str(TseSettingsKeys.validTime), check.validTime)
                                TseCheckItem(str(TseSettingsKeys.passedSelfTest), check.passedSelfTest)
                            }
                            Text(
                                text = "${str(TseSettingsKeys.remainingSignatures)}: ${check.remainingSignatures}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Flash Health Section (animated)
                AnimatedVisibility(
                    visible = flashHealth != null,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    flashHealth?.let { flash ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()

                            // Flash health header
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = if (flash.needsReplacement) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = str(TseSettingsKeys.flashHealthInfo),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }

                            if (flash.needsReplacement) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = str(TseSettingsKeys.needsReplacement),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            TseProgressItem(
                                label = str(TseSettingsKeys.spareBlocks),
                                percentage = flash.percentageRemainingSpareBlocks,
                            )
                            TseProgressItem(
                                label = str(TseSettingsKeys.eraseCycles),
                                percentage = flash.percentageRemainingEraseCounts,
                            )
                            TseProgressItem(
                                label = str(TseSettingsKeys.dataRetention),
                                percentage = flash.percentageRemainingTenYearsDataRetention,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single row displaying a label-value pair.
 *
 * @param label The label text
 * @param value The value text
 */
@Composable
fun TseInfoRow(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Check item with icon indicating pass/fail status.
 *
 * @param label The check label
 * @param checked Whether the check passed
 */
@Composable
private fun TseCheckItem(
    label: String,
    checked: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (checked) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (checked) LocalPosColors.current.posAvailable else MaterialTheme.colorScheme.error,
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Progress item showing a label with percentage and colored progress bar.
 *
 * @param label The progress label
 * @param percentage The percentage value (0-100)
 */
@Composable
private fun TseProgressItem(
    label: String,
    percentage: Int,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$percentage%", style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { percentage / 100f },
            modifier = Modifier.fillMaxWidth(),
            color =
                when {
                    percentage < 20 -> MaterialTheme.colorScheme.error
                    percentage < 50 -> LocalPosColors.current.posOccupied
                    else -> MaterialTheme.colorScheme.primary
                },
        )
    }
}
