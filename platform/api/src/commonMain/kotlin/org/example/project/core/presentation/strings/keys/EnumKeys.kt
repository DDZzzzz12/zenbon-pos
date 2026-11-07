package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object EnumKeys {
    // AccountMappingType
    val accountMappingTypePaymentType = StringKey("enum.account_mapping_type.payment_type")
    val accountMappingTypeProductCategory = StringKey("enum.account_mapping_type.product_category")
    val accountMappingTypeExpenseCategory = StringKey("enum.account_mapping_type.expense_category")
    val accountMappingTypeVatRate = StringKey("enum.account_mapping_type.vat_rate")
    val accountMappingTypeEmployeeType = StringKey("enum.account_mapping_type.employee_type")
    val accountMappingTypeTipCategory = StringKey("enum.account_mapping_type.tip_category")
    val accountMappingTypeDiscountType = StringKey("enum.account_mapping_type.discount_type")
    val accountMappingTypeCashRegister = StringKey("enum.account_mapping_type.cash_register")
    val accountMappingTypeLocation = StringKey("enum.account_mapping_type.location")
    val accountMappingTypeSpecialTransaction = StringKey("enum.account_mapping_type.special_transaction")

    // DebitCreditIndicator
    val debitCreditIndicatorDebit = StringKey("enum.debit_credit_indicator.debit")
    val debitCreditIndicatorCredit = StringKey("enum.debit_credit_indicator.credit")

    // EmployeeRole
    val employeeRoleTerminal = StringKey("enum.employee_role.terminal")
    val employeeRoleCashier = StringKey("enum.employee_role.cashier")
    val employeeRoleManager = StringKey("enum.employee_role.manager")
    val employeeRoleAdmin = StringKey("enum.employee_role.admin")

    // EmploymentStatus
    val employmentStatusActive = StringKey("enum.employment_status.active")
    val employmentStatusInactive = StringKey("enum.employment_status.inactive")
    val employmentStatusTerminated = StringKey("enum.employment_status.terminated")
    val employmentStatusOnLeave = StringKey("enum.employment_status.on_leave")

    // ItemStatus
    val itemStatusOpen = StringKey("enum.item_status.open")
    val itemStatusPaid = StringKey("enum.item_status.paid")
    val itemStatusCancelled = StringKey("enum.item_status.cancelled")

    // KassenbuchEntryType
    val kassenbuchEntryTypeCashTransferIn = StringKey("enum.kassenbuch_entry_type.cash_transfer_in")
    val kassenbuchEntryTypeCashTransferOut = StringKey("enum.kassenbuch_entry_type.cash_transfer_out")
    val kassenbuchEntryTypeCashSale = StringKey("enum.kassenbuch_entry_type.cash_sale")
    val kassenbuchEntryTypeCashPayment = StringKey("enum.kassenbuch_entry_type.cash_payment")
    val kassenbuchEntryTypeCashDeposit = StringKey("enum.kassenbuch_entry_type.cash_deposit")
    val kassenbuchEntryTypePrivateDeposit = StringKey("enum.kassenbuch_entry_type.private_deposit")
    val kassenbuchEntryTypeCashExpense = StringKey("enum.kassenbuch_entry_type.cash_expense")
    val kassenbuchEntryTypeCashWithdrawal = StringKey("enum.kassenbuch_entry_type.cash_withdrawal")
    val kassenbuchEntryTypePrivateWithdrawal = StringKey("enum.kassenbuch_entry_type.private_withdrawal")
    val kassenbuchEntryTypeRefund = StringKey("enum.kassenbuch_entry_type.refund")
    val kassenbuchEntryTypeCashSurplus = StringKey("enum.kassenbuch_entry_type.cash_surplus")
    val kassenbuchEntryTypeCashShortage = StringKey("enum.kassenbuch_entry_type.cash_shortage")
    val kassenbuchEntryTypeOpeningBalance = StringKey("enum.kassenbuch_entry_type.opening_balance")
    val kassenbuchEntryTypeClosingBalance = StringKey("enum.kassenbuch_entry_type.closing_balance")
    val kassenbuchEntryTypeCorrectionIn = StringKey("enum.kassenbuch_entry_type.correction_in")
    val kassenbuchEntryTypeCorrectionOut = StringKey("enum.kassenbuch_entry_type.correction_out")
    val kassenbuchEntryTypeChangeFund = StringKey("enum.kassenbuch_entry_type.change_fund")

    // OrderStatus
    val orderStatusDraft = StringKey("enum.order_status.draft")
    val orderStatusConfirmed = StringKey("enum.order_status.confirmed")
    val orderStatusInPreparation = StringKey("enum.order_status.in_preparation")
    val orderStatusReady = StringKey("enum.order_status.ready")
    val orderStatusServed = StringKey("enum.order_status.served")
    val orderStatusCompleted = StringKey("enum.order_status.completed")
    val orderStatusCancelled = StringKey("enum.order_status.cancelled")
    val orderStatusRefunded = StringKey("enum.order_status.refunded")

    // PaymentStatus
    val paymentStatusCompleted = StringKey("enum.payment_status.completed")
    val paymentStatusRefunded = StringKey("enum.payment_status.refunded")

    // PrinterType
    val printerTypeUsb = StringKey("enum.printer_type.usb")
    val printerTypeNetwork = StringKey("enum.printer_type.network")
    val printerTypeIpp = StringKey("enum.printer_type.ipp")

    // ProductType
    val productTypeFood = StringKey("enum.product_type.food")
    val productTypeDrinks = StringKey("enum.product_type.drinks")
    val productTypeRetail = StringKey("enum.product_type.retail")
    val productTypeService = StringKey("enum.product_type.service")
    val productTypeOthers = StringKey("enum.product_type.others")
    val productTypeVoucher = StringKey("enum.product_type.voucher")
    val systemCategoryVoucher = StringKey("enum.system_category.voucher")
    val systemCategoryPfand = StringKey("enum.system_category.pfand")

    // RoomElementType
    val roomElementTypeWall = StringKey("enum.room_element_type.wall")
    val roomElementTypeDoor = StringKey("enum.room_element_type.door")
    val roomElementTypeWindow = StringKey("enum.room_element_type.window")
    val roomElementTypeCounter = StringKey("enum.room_element_type.counter")
    val roomElementTypeDecoration = StringKey("enum.room_element_type.decoration")

    // TableStatus
    val tableStatusFree = StringKey("enum.table_status.free")
    val tableStatusOccupied = StringKey("enum.table_status.occupied")
    val tableStatusReserved = StringKey("enum.table_status.reserved")

    // TipRecipient
    val tipRecipientEmployee = StringKey("enum.tip_recipient.employee")
    val tipRecipientOwner = StringKey("enum.tip_recipient.owner")

    // VoucherSourceType
    val voucherSourceInternal = StringKey("enum.voucher_source_type.internal")
    val voucherSourceLegacy = StringKey("enum.voucher_source_type.legacy")
    val voucherSourceExternal = StringKey("enum.voucher_source_type.external")
    val voucherSourcePromotional = StringKey("enum.voucher_source_type.promotional")
    val voucherSourceGiftCard = StringKey("enum.voucher_source_type.gift_card")

    // TransactionType
    val transactionTypeBill = StringKey("enum.transaction_type.bill")

    // TseEntityType
    val tseEntityTypeBill = StringKey("enum.tse_entity_type.bill")
    val tseEntityTypeCancellation = StringKey("enum.tse_entity_type.cancellation")
    val tseEntityTypeOrder = StringKey("enum.tse_entity_type.order")
    val tseEntityTypeVoucher = StringKey("enum.tse_entity_type.voucher")
    val tseEntityTypeKassenbuch = StringKey("enum.tse_entity_type.kassenbuch")

    // TseFailureBehavior
    val tseFailureBehaviorQueue = StringKey("enum.tse_failure_behavior.queue")
    val tseFailureBehaviorFail = StringKey("enum.tse_failure_behavior.fail")

    // TseMode
    val tseModeSimulation = StringKey("enum.tse_mode.simulation")
    val tseModeDisabled = StringKey("enum.tse_mode.disabled")
    val tseModeNormal = StringKey("enum.tse_mode.normal")

    // TseSigningStatus
    val tseSigningStatusPending = StringKey("enum.tse_signing_status.pending")
    val tseSigningStatusProcessing = StringKey("enum.tse_signing_status.processing")
    val tseSigningStatusQueued = StringKey("enum.tse_signing_status.queued")
    val tseSigningStatusSigned = StringKey("enum.tse_signing_status.signed")
    val tseSigningStatusFailed = StringKey("enum.tse_signing_status.failed")
    val tseSigningStatusCancelled = StringKey("enum.tse_signing_status.cancelled")
    val tseSigningStatusDisabled = StringKey("enum.tse_signing_status.disabled")
    val tseSigningStatusSimulation = StringKey("enum.tse_signing_status.simulation")
    val tseSigningStatusNotApplicable = StringKey("enum.tse_signing_status.not_applicable")

    // Document, VAT class, TSE display labels (adminApp fix-conventions)
    val documentTypeInvoice = StringKey("enum.document_type.invoice")
    val documentTypeOutgoingInvoice = StringKey("enum.document_type.outgoing_invoice")
    val documentTypeBankStatement = StringKey("enum.document_type.bank_statement")
    val documentTypeContract = StringKey("enum.document_type.contract")
    val documentTypeEigenbeleg = StringKey("enum.document_type.eigenbeleg")
    val documentTypeZbonReport = StringKey("enum.document_type.zbon_report")
    val vatClassStandard = StringKey("enum.vat_class.standard")
    val vatClassReduced = StringKey("enum.vat_class.reduced")
    val vatClassZero = StringKey("enum.vat_class.zero")
    val tseInitInitialized = StringKey("enum.tse_init_state.initialized")
    val tseInitUninitialized = StringKey("enum.tse_init_state.uninitialized")
    val tseInitDecommissioned = StringKey("enum.tse_init_state.decommissioned")
    val tseLogTimeUnix = StringKey("enum.tse_log_time_format.unix_time")
    val tseLogTimeUtc = StringKey("enum.tse_log_time_format.utc_time")
    val tseLogTimeUtcSeconds = StringKey("enum.tse_log_time_format.utc_time_with_seconds")
    val tseLogTimeGeneralized = StringKey("enum.tse_log_time_format.generalized_time")
    val tseLogEntryTransaction = StringKey("enum.tse_log_entry_type.transaction")
    val tseLogEntrySystem = StringKey("enum.tse_log_entry_type.system")
    val tseLogEntryAudit = StringKey("enum.tse_log_entry_type.audit")
    val tseExplTanrWithoutPos = StringKey("enum.tse_explanation_category.tanr_without_pos_record")
    val tseExplOrphanAbort = StringKey("enum.tse_explanation_category.orphan_abort")
    val tseExplDuplicateSignature = StringKey("enum.tse_explanation_category.duplicate_signature")
    val tseExplSuperseded = StringKey("enum.tse_explanation_category.superseded")
    val tseExplDuplicateTanr = StringKey("enum.tse_explanation_category.duplicate_tanr")
    val tseExplSignatureCounterGap = StringKey("enum.tse_explanation_category.signature_counter_gap")
    val tseExplTseOutage = StringKey("enum.tse_explanation_category.tse_outage")
    val tseExplOther = StringKey("enum.tse_explanation_category.other")
    val voucherSourceDefaultName = StringKey("enum.voucher_source.default_name")
    val voucherSourceDefaultDescription = StringKey("enum.voucher_source.default_description")

    // Pre-filled name/description of a NEW voucher source per type (INTERNAL uses the two above).
    val voucherSourceDefaultNameLegacy = StringKey("enum.voucher_source.default_name.legacy")
    val voucherSourceDefaultDescriptionLegacy = StringKey("enum.voucher_source.default_description.legacy")
    val voucherSourceDefaultNameExternal = StringKey("enum.voucher_source.default_name.external")
    val voucherSourceDefaultDescriptionExternal = StringKey("enum.voucher_source.default_description.external")
    val voucherSourceDefaultNamePromotional = StringKey("enum.voucher_source.default_name.promotional")
    val voucherSourceDefaultDescriptionPromotional = StringKey("enum.voucher_source.default_description.promotional")
    val voucherSourceDefaultNameGiftCard = StringKey("enum.voucher_source.default_name.gift_card")
    val voucherSourceDefaultDescriptionGiftCard = StringKey("enum.voucher_source.default_description.gift_card")
}
