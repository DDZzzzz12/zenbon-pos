package org.example.project.admin.features.voucher.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosStatusChip

/**
 * The three visible voucher states, derived rather than taken from one field: INVALIDATED wins,
 * then fully-redeemed, then active. [VoucherStatusPill] derives from this single
 * classification so the status logic exists in one place.
 */
private enum class VoucherStatusKind { INVALIDATED, REDEEMED, ACTIVE }

private fun VoucherDto.VoucherResponseDto.statusKind(): VoucherStatusKind =
    when {
        status == "INVALIDATED" -> VoucherStatusKind.INVALIDATED
        isFullyRedeemed -> VoucherStatusKind.REDEEMED
        else -> VoucherStatusKind.ACTIVE
    }

@Composable
private fun VoucherStatusKind.label(): String =
    when (this) {
        VoucherStatusKind.INVALIDATED -> str(VoucherManagementKeys.invalidated)
        VoucherStatusKind.REDEEMED -> str(VoucherManagementKeys.redeemed)
        VoucherStatusKind.ACTIVE -> str(VoucherManagementKeys.active)
    }

/** Status chip for a voucher. */
@Composable
fun VoucherStatusPill(
    voucher: VoucherDto.VoucherResponseDto,
    modifier: Modifier = Modifier,
) {
    val kind = voucher.statusKind()
    val text = kind.label()
    val background =
        when (kind) {
            VoucherStatusKind.INVALIDATED -> MaterialTheme.colorScheme.errorContainer
            VoucherStatusKind.REDEEMED -> MaterialTheme.colorScheme.surfaceVariant
            VoucherStatusKind.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        }
    val foreground =
        when (kind) {
            VoucherStatusKind.INVALIDATED -> MaterialTheme.colorScheme.onErrorContainer
            VoucherStatusKind.REDEEMED -> MaterialTheme.colorScheme.onSurfaceVariant
            VoucherStatusKind.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        }
    PosStatusChip(text = text, backgroundColor = background, textColor = foreground, modifier = modifier)
}
