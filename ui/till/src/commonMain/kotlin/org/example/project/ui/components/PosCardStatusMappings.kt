package org.example.project.ui.components

fun TableCardStatus.toPosCardStatus(): PosCardStatus =
    when (this) {
        TableCardStatus.FREE -> PosCardStatus.Available
        TableCardStatus.OCCUPIED -> PosCardStatus.Occupied
        TableCardStatus.RESERVED -> PosCardStatus.Reserved
        TableCardStatus.BILLING -> PosCardStatus.Billing
        TableCardStatus.INACTIVE -> PosCardStatus.Neutral
    }

fun OrderItemCardStatus.toPosCardStatus(): PosCardStatus =
    when (this) {
        OrderItemCardStatus.PAID -> PosCardStatus.Completed
        OrderItemCardStatus.OPEN -> PosCardStatus.Active
        OrderItemCardStatus.CANCELLED -> PosCardStatus.Cancelled
    }

fun ReservationCardStatus.toPosCardStatus(): PosCardStatus =
    when (this) {
        ReservationCardStatus.CONFIRMED -> PosCardStatus.Pending
        ReservationCardStatus.SEATED -> PosCardStatus.Available
        ReservationCardStatus.COMPLETED -> PosCardStatus.Completed
        ReservationCardStatus.CANCELLED -> PosCardStatus.Cancelled
        ReservationCardStatus.NO_SHOW -> PosCardStatus.Error
    }

fun VoucherCardStatus.toPosCardStatus(): PosCardStatus =
    when (this) {
        VoucherCardStatus.ACTIVE -> PosCardStatus.Active
        VoucherCardStatus.PARTIALLY_REDEEMED -> PosCardStatus.Pending
        VoucherCardStatus.FULLY_REDEEMED -> PosCardStatus.Completed
        VoucherCardStatus.EXPIRED -> PosCardStatus.Error
        VoucherCardStatus.INVALIDATED -> PosCardStatus.Error
    }
