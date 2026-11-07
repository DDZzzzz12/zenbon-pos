package org.example.project.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.TseDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.tseLogTimeFormatLabel
import org.example.project.core.utils.formatInstantShortDateTimeSeconds
import org.example.project.core.utils.parseTseQrContent

/**
 * Displays parsed TSE QR code fields in a labeled row layout.
 * Reusable across [PosBillDetailPanel], order detail panels, etc.
 */
@Composable
fun TseField(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Renders all parsed TSE transaction fields from a DSFinV-K QR content string.
 * Shows nothing when [tseQrCodeContent] is null or unparseable.
 */
@Composable
fun TseQrCodeSection(
    tseQrCodeContent: String?,
    modifier: Modifier = Modifier,
) {
    tseQrCodeContent?.let { qrContent ->
        val tseQr = parseTseQrContent(qrContent) ?: return@let
        Column(modifier = modifier.padding(top = 16.dp)) {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = str(TseDetailKeys.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            TseField(str(TseDetailKeys.transactionNumber), tseQr.transactionNumber)
            TseField(str(TseDetailKeys.signatureCounter), tseQr.signatureCounter)
            TseField(str(TseDetailKeys.signatureAlgorithm), tseQr.signatureAlgorithm)
            // Local date-time with seconds for reading (admin20 NOTE-D), then the raw UTC instants: those are
            // byte-identical to the printed receipt (AEAO zu § 146a AO Nr. 2.4.4) and stay the fiscal reference.
            val language = LocalAppLanguage.current
            TseField(str(TseDetailKeys.processStart), tseQr.startTime.formatInstantShortDateTimeSeconds(language))
            TseField(str(TseDetailKeys.processEnd), tseQr.logTime.formatInstantShortDateTimeSeconds(language))
            TseField(str(TseDetailKeys.processStartUtc), tseQr.startTime)
            TseField(str(TseDetailKeys.processEndUtc), tseQr.logTime)
            TseField(str(TseDetailKeys.clientId), tseQr.kassenSeriennummer)
            TseField(str(TseDetailKeys.logTimeFormat), tseLogTimeFormatLabel(tseQr.logTimeFormat, language))
            TseField(str(TseDetailKeys.signature), tseQr.signature)
            TseField(str(TseDetailKeys.publicKey), tseQr.publicKey)
        }
    }
}
