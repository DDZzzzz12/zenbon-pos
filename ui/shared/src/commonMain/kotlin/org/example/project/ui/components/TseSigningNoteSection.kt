package org.example.project.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.tse.TseSigningNoteDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.TseDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime

/**
 * The Nachsignierung record of a bill or cash-book entry the TSE could not sign immediately:
 * whether it is still pending, signed late or abandoned, why, and since when. Shows nothing when
 * [note] is null — the Vorgang was signed on time.
 */
@Composable
fun TseSigningNoteSection(
    note: TseSigningNoteDto?,
    modifier: Modifier = Modifier,
) {
    note ?: return
    val language = LocalAppLanguage.current

    // formatShortDateTime returns an unparseable value unchanged, so no fallback is needed here.
    fun display(isoLocal: String) = isoLocal.formatShortDateTime(language)

    val status =
        when {
            note.signedAt != null -> str(TseDetailKeys.lateSigningDone, display(note.signedAt!!))
            note.abandoned -> str(TseDetailKeys.lateSigningAbandoned)
            else -> str(TseDetailKeys.lateSigningPending)
        }
    Column(modifier = modifier.padding(top = 16.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            text = str(TseDetailKeys.lateSigningTitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (note.pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        TseField(str(TseDetailKeys.lateSigningReason), note.reasonText)
        TseField(str(TseDetailKeys.lateSigningQueuedAt), display(note.queuedAt))
    }
}
