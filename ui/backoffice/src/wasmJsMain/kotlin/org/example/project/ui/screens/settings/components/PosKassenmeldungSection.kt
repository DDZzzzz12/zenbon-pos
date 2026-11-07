package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.features.settings.presentation.state.KassenMeldungState
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosTextButton
import kotlin.time.Instant

/** Serial-number group length: an 8-hex group is easy to read back and compare. */
private const val SERIAL_GROUP = 8

/** Below this row width the label goes above the value; a 40/60 split leaves a serial number ~190dp. */
private val INFO_ROW_STACK_BELOW = 420.dp

/**
 * The BSI certification id's fixed prefix (`BSI-K-TR-0123`). A scheme identifier printed on the
 * certificate, not UI text, so it is deliberately not a translatable string.
 */
private const val BSI_CERT_ID_PREFIX = "BSI-K-TR-"

/**
 * The §146a notification report. Its only action, "PDF exportieren", lives in the page header (the
 * host, `KassenmeldungSection`), not at the bottom of the card. Tonal like the other cards in
 * the Compliance group (UI-FG-16), not elevated.
 */
@Composable
fun PosKassenmeldungSection(state: KassenMeldungState) {
    val language = LocalAppLanguage.current
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (state.data == null) {
                    Text(
                        text = str(DatevSettingsKeys.kassenmeldungNotConfigured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val data = state.data

                    // Section 1: Betriebsstätte
                    SectionHeader(str(DatevSettingsKeys.kassenmeldungBetriebsstaette))
                    InfoRow(str(DatevSettingsKeys.kassenmeldungBetriebsstaette), data.betriebsstaetteName)
                    InfoRow(
                        str(DatevSettingsKeys.kassenmeldungAdresse),
                        buildAddressLine(data),
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Section 2: Elektronisches Aufzeichnungssystem (eAS)
                    SectionHeader(str(DatevSettingsKeys.kassenmeldungEas))
                    InfoRow(str(DatevSettingsKeys.kasseSeriennr), data.kasseSeriennr)
                    InfoRow(str(DatevSettingsKeys.kasseBrand), data.kasseBrand)
                    InfoRow(str(DatevSettingsKeys.kasseModell), data.kasseModell)
                    InfoRow(str(DatevSettingsKeys.kasseSwBrand), data.kasseSwBrand)
                    InfoRow(str(DatevSettingsKeys.kasseSwVersion), data.kasseSwVersion)
                    InfoRow(str(DatevSettingsKeys.artDesAufzeichnungssystems), data.artDesAufzeichnungssystems)
                    // One date format on the whole report (UI-FG-16): the server sends ISO dates.
                    data.anschaffung?.let { InfoRow(str(DatevSettingsKeys.anschaffung), it.formatShortDate(language)) }
                    InfoRow(str(DatevSettingsKeys.inbetriebnahme), data.inbetriebnahme.formatShortDate(language))

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Section 3: TSE
                    SectionHeader(str(DatevSettingsKeys.kassenmeldungTse))
                    val tseSerial = data.tseSerialNumberHex
                    if (tseSerial != null) {
                        SerialRow(str(DatevSettingsKeys.tseSeriennummer), tseSerial)
                        data.tseDescription?.let {
                            InfoRow(str(DatevSettingsKeys.tseBeschreibung), it)
                        }
                        data.tseCertificateExpirationDate?.let {
                            InfoRow(str(DatevSettingsKeys.tseZertifikatBis), epochIsoDate(it).formatShortDate(language))
                        }
                        data.tseBsiCertId?.let {
                            InfoRow(str(DatevSettingsKeys.tseBsiCertId), "$BSI_CERT_ID_PREFIX$it")
                        }
                        data.tseFormFactor?.let {
                            InfoRow(str(DatevSettingsKeys.tseBauform), it)
                        }
                        Text(
                            text = str(DatevSettingsKeys.kassenmeldungTseSourceNote),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = str(DatevSettingsKeys.kassenmeldungTseHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The TSE serial, which the operator transcribes into the ELSTER form: monospace, in groups of
 * [SERIAL_GROUP] characters so a line can only break between groups (it used to wrap mid-token
 * wherever the column ended), with a copy button so it need not be transcribed at all (UI-FG-16).
 */
@Composable
private fun SerialRow(
    label: String,
    serial: String,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(serial) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoLabel(label, Modifier.fillMaxWidth())
        Text(
            text = serial.chunked(SERIAL_GROUP).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextButton(
            text = str(if (copied) CommonKeys.errorDialogCopiedToClipboard else AdminStringKeys.KassenmeldungCopySerial),
            icon = Icons.Default.ContentCopy,
            onClick = {
                clipboard.setText(AnnotatedString(serial))
                copied = true
            },
            contentPadding = PaddingValues(horizontal = 0.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < INFO_ROW_STACK_BELOW) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                InfoLabel(label, Modifier.fillMaxWidth())
                InfoValue(value, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoLabel(label, Modifier.weight(0.4f))
                InfoValue(value, Modifier.weight(0.6f))
            }
        }
    }
}

@Composable
private fun InfoLabel(
    text: String,
    modifier: Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun InfoValue(
    text: String,
    modifier: Modifier,
) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

private fun buildAddressLine(data: ShopConfigurationDto.KassenMeldungResponseDto): String {
    val parts =
        listOfNotNull(
            data.betriebsstaetteStreet.ifBlank { null },
            "${data.betriebsstaetteZip} ${data.betriebsstaetteCity}".trim().ifBlank { null },
            data.betriebsstaetteCountry.ifBlank { null },
        )
    return parts.joinToString(", ")
}

/** An epoch-seconds instant as its ISO calendar date in the system zone, for [formatShortDate]. */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun epochIsoDate(epochSeconds: Long): String =
    Instant
        .fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()
