package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this KassenbuchEntryType.
 *
 * @param language The target language for localization
 * @return Localized display name for the cash book entry type
 */
fun KassenbuchEntryType.getName(language: Language): String =
    when (this) {
        KassenbuchEntryType.CASH_TRANSFER_IN -> getString(EnumKeys.kassenbuchEntryTypeCashTransferIn, language)
        KassenbuchEntryType.CASH_TRANSFER_OUT -> getString(EnumKeys.kassenbuchEntryTypeCashTransferOut, language)
        KassenbuchEntryType.CASH_SALE -> getString(EnumKeys.kassenbuchEntryTypeCashSale, language)
        KassenbuchEntryType.CASH_PAYMENT -> getString(EnumKeys.kassenbuchEntryTypeCashPayment, language)
        KassenbuchEntryType.CASH_DEPOSIT -> getString(EnumKeys.kassenbuchEntryTypeCashDeposit, language)
        KassenbuchEntryType.PRIVATE_DEPOSIT -> getString(EnumKeys.kassenbuchEntryTypePrivateDeposit, language)
        KassenbuchEntryType.CASH_EXPENSE -> getString(EnumKeys.kassenbuchEntryTypeCashExpense, language)
        KassenbuchEntryType.CASH_WITHDRAWAL -> getString(EnumKeys.kassenbuchEntryTypeCashWithdrawal, language)
        KassenbuchEntryType.PRIVATE_WITHDRAWAL -> getString(EnumKeys.kassenbuchEntryTypePrivateWithdrawal, language)
        KassenbuchEntryType.REFUND -> getString(EnumKeys.kassenbuchEntryTypeRefund, language)
        KassenbuchEntryType.CASH_SURPLUS -> getString(EnumKeys.kassenbuchEntryTypeCashSurplus, language)
        KassenbuchEntryType.CASH_SHORTAGE -> getString(EnumKeys.kassenbuchEntryTypeCashShortage, language)
        KassenbuchEntryType.OPENING_BALANCE -> getString(EnumKeys.kassenbuchEntryTypeOpeningBalance, language)
        KassenbuchEntryType.CLOSING_BALANCE -> getString(EnumKeys.kassenbuchEntryTypeClosingBalance, language)
        KassenbuchEntryType.CORRECTION_IN -> getString(EnumKeys.kassenbuchEntryTypeCorrectionIn, language)
        KassenbuchEntryType.CORRECTION_OUT -> getString(EnumKeys.kassenbuchEntryTypeCorrectionOut, language)
        KassenbuchEntryType.CHANGE_FUND -> getString(EnumKeys.kassenbuchEntryTypeChangeFund, language)
    }

/**
 * Returns the localized description for this KassenbuchEntryType.
 *
 * @param language The target language for localization
 * @return Localized description for the cash book entry type
 */
fun KassenbuchEntryType.getDescription(language: Language): String =
    when (this) {
        KassenbuchEntryType.CASH_TRANSFER_IN ->
            when (language) {
                Language.GERMAN ->
                    "Bargeldzugang aus Bank, Tresor oder einer anderen Kasse " +
                        "(z.B. Wechselgeld-Auffüllung)"
                Language.ENGLISH ->
                    "Cash arriving from the bank, the safe or another register " +
                        "(e.g. topping up the change float)"
                Language.CHINESE -> "从银行、保险柜或其他收银机转入的现金（例如补充找零备用金）"
            }
        KassenbuchEntryType.CASH_TRANSFER_OUT ->
            when (language) {
                Language.GERMAN ->
                    "Bargeldabgang an Bank, Tresor oder eine andere Kasse " +
                        "(z.B. Tageseinnahme in den Tresor)"
                Language.ENGLISH ->
                    "Cash leaving for the bank, the safe or another register " +
                        "(e.g. the day's takings into the safe)"
                Language.CHINESE -> "转出至银行、保险柜或其他收银机的现金（例如将当日营业收入存入保险柜）"
            }
        KassenbuchEntryType.CASH_SALE ->
            when (language) {
                Language.GERMAN -> "Barverkauf aus Kassentransaktion"
                Language.ENGLISH -> "Cash sale from point-of-sale transaction"
                Language.CHINESE -> "来自收银台交易的现金销售"
            }
        KassenbuchEntryType.CASH_PAYMENT ->
            when (language) {
                Language.GERMAN -> "Barzahlung vom Kunden erhalten"
                Language.ENGLISH -> "Cash payment received from customer"
                Language.CHINESE -> "从客户收到的现金付款"
            }
        // GV_TYP „Einzahlung" — Anhang C p. 58: the catch-all for inflows the DSFinV-K
        // standard fields cannot express. Deliberately does NOT name the Tresor: cash
        // arriving from the safe is a Geldtransit, and naming it here steered operators
        // onto the wrong GV_TYP.
        KassenbuchEntryType.CASH_DEPOSIT ->
            when (language) {
                Language.GERMAN -> "Sonstige Bareinlage, die keinem anderen Geschäftsvorfall entspricht"
                Language.ENGLISH -> "Miscellaneous cash inflow that no other business case covers"
                Language.CHINESE -> "其他业务类型无法涵盖的现金流入"
            }
        KassenbuchEntryType.PRIVATE_DEPOSIT ->
            when (language) {
                Language.GERMAN -> "Private Bareinlage des Unternehmers in die Kasse"
                Language.ENGLISH -> "Private cash deposit by the business owner"
                Language.CHINESE -> "企业主向收银机的私人现金存入"
            }
        KassenbuchEntryType.CASH_EXPENSE ->
            when (language) {
                Language.GERMAN -> "Betriebsausgabe bar bezahlt"
                Language.ENGLISH -> "Business expense paid with cash"
                Language.CHINESE -> "用现金支付的营业费用"
            }
        KassenbuchEntryType.CASH_WITHDRAWAL ->
            when (language) {
                Language.GERMAN -> "Barentnahme aus der Kasse"
                Language.ENGLISH -> "Cash withdrawal from register"
                Language.CHINESE -> "从收银机提取现金"
            }
        KassenbuchEntryType.PRIVATE_WITHDRAWAL ->
            when (language) {
                Language.GERMAN -> "Private Barentnahme des Geschäftsinhabers"
                Language.ENGLISH -> "Private cash withdrawal by business owner"
                Language.CHINESE -> "企业主的私人现金提取"
            }
        KassenbuchEntryType.REFUND ->
            when (language) {
                Language.GERMAN -> "Barrückerstattung an Kunden"
                Language.ENGLISH -> "Cash refund issued to customer"
                Language.CHINESE -> "向客户发放的现金退款"
            }
        KassenbuchEntryType.CASH_SURPLUS ->
            when (language) {
                Language.GERMAN -> "Kassenüberschuss bei Abstimmung festgestellt"
                Language.ENGLISH -> "Cash surplus found during reconciliation"
                Language.CHINESE -> "对账时发现的现金盈余"
            }
        KassenbuchEntryType.CASH_SHORTAGE ->
            when (language) {
                Language.GERMAN -> "Kassenfehlbetrag bei Abstimmung festgestellt"
                Language.ENGLISH -> "Cash shortage found during reconciliation"
                Language.CHINESE -> "对账时发现的现金短缺"
            }
        KassenbuchEntryType.OPENING_BALANCE ->
            when (language) {
                Language.GERMAN -> "Anfangsbestand der Kasse für Abrechnungszeitraum"
                Language.ENGLISH -> "Opening cash balance for accounting period"
                Language.CHINESE -> "会计期间的期初现金余额"
            }
        KassenbuchEntryType.CLOSING_BALANCE ->
            when (language) {
                Language.GERMAN -> "Endbestand der Kasse für Abrechnungszeitraum"
                Language.ENGLISH -> "Closing cash balance for accounting period"
                Language.CHINESE -> "会计期间的期末现金余额"
            }
        KassenbuchEntryType.CORRECTION_IN ->
            when (language) {
                Language.GERMAN -> "Korrekturbuchung für vorherige Buchungsfehler"
                Language.ENGLISH -> "Correction entry for previous booking errors"
                Language.CHINESE -> "针对先前记账错误的更正分录"
            }
        KassenbuchEntryType.CORRECTION_OUT ->
            when (language) {
                Language.GERMAN -> "Korrekturbuchung für vorherige Buchungsfehler"
                Language.ENGLISH -> "Correction entry for previous booking errors"
                Language.CHINESE -> "针对先前记账错误的更正分录"
            }
        KassenbuchEntryType.CHANGE_FUND ->
            when (language) {
                Language.GERMAN -> "Wechselgeld für den Kassenbetrieb"
                Language.ENGLISH -> "Change fund for register operation"
                Language.CHINESE -> "收银机运营找零备用金"
            }
    }
