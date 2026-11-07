package org.example.project.features.settings.presentation.components.printer

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.PrinterSettingsKeys

/**
 * Localized labels of the print-job enums, shared by the job card and the history filter chips (the
 * chips used to print the raw enum names `FAILED`, `BILL`, UI review follow-up 5).
 */
fun printTypeKey(type: PrinterDto.PrintType): StringKey =
    when (type) {
        PrinterDto.PrintType.BILL -> PrinterSettingsKeys.printTypeBill
        PrinterDto.PrintType.ORDER -> PrinterSettingsKeys.printTypeOrder
        PrinterDto.PrintType.ZBON -> PrinterSettingsKeys.printTypeZBon
        PrinterDto.PrintType.TEST_PRINT -> PrinterSettingsKeys.printTypeTestPrint
        PrinterDto.PrintType.REFERENCE_PRINT -> PrinterSettingsKeys.printTypeReferencePrint
    }

fun printJobStatusKey(status: PrinterDto.PrintJobStatus): StringKey =
    when (status) {
        PrinterDto.PrintJobStatus.SUCCESS -> PrinterSettingsKeys.printJobStatusSuccess
        PrinterDto.PrintJobStatus.FAILED -> PrinterSettingsKeys.printJobStatusFailed
        PrinterDto.PrintJobStatus.PENDING -> PrinterSettingsKeys.printJobStatusPending
    }
