package org.example.project.admin.core.presentation.belegfluss

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.beleg.BELEG_PROGRESS_STEPS
import org.example.project.ui.components.beleg.PosRowProgress
import org.example.project.ui.components.beleg.PosStatusDot

/** The four progress squares of an invoice row, labelled "Schritt n von 4" for assistive technology. */
@Composable
fun InvoiceRowProgress(
    bucket: InvoiceListDto.Bucket,
    modifier: Modifier = Modifier,
) {
    val steps = InvoiceFlow.stepsOf(bucket)
    PosRowProgress(
        steps = steps,
        description = str(AdminStringKeys.BelegflowProgressStep, steps, BELEG_PROGRESS_STEPS),
        modifier = modifier,
    )
}

/** Status of an invoice row as dot + word: Zu erfassen grey, Zu prüfen blue, Offen amber, Überfällig red, Erledigt green. */
@Composable
fun InvoiceRowStatus(
    bucket: InvoiceListDto.Bucket,
    modifier: Modifier = Modifier,
) {
    PosStatusDot(label = str(InvoiceFlow.statusLabelKey(bucket)), tone = InvoiceFlow.toneOf(bucket), modifier = modifier)
}
