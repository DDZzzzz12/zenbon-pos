package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * The on-screen name of this document type: singular, sentence case, in the UI language.
 *
 * [DocumentType.displayNameDe] stays what it is (upper-case archive folder names); screens use
 * this instead.
 */
fun DocumentType.getName(language: Language): String =
    when (this) {
        DocumentType.INVOICE -> getString(EnumKeys.documentTypeInvoice, language)
        DocumentType.OUTGOING_INVOICE -> getString(EnumKeys.documentTypeOutgoingInvoice, language)
        DocumentType.BANK_STATEMENT -> getString(EnumKeys.documentTypeBankStatement, language)
        DocumentType.CONTRACTS -> getString(EnumKeys.documentTypeContract, language)
        DocumentType.EIGENBELEG -> getString(EnumKeys.documentTypeEigenbeleg, language)
        DocumentType.ZBON_REPORT -> getString(EnumKeys.documentTypeZbonReport, language)
    }
