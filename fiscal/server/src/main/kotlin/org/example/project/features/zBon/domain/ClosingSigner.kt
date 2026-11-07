package org.example.project.features.zBon.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.terminal.domain.SigningDefect
import org.example.project.features.terminal.domain.TSE_DISABLED_CLIENT_ID
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.features.terminal.domain.requireSigningTerminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi

/**
 * Which TSE client signs a closing's cash movements — the Kassendifferenz, cash tip payouts, the
 * top-up and the daily Geldtransit. The `CASH_SALE` aggregate is not signed at all (spec
 * 2026-10-09, AEAO zu § 146a Nr. 1.11.2).
 *
 * 1. The till designated on the fiscal book ([CashBook.signingTerminal]) signs, whichever device
 *    triggered the closing. An unfit designated till is a refusal, never a silent fallback.
 * 2. With none designated, the calling till signs — the behaviour before the designation existed, so
 *    an upgraded install keeps closing at its tills untouched.
 * 3. With none designated and a caller that is not a till (the adminApp's `admin-web-*`, an unknown
 *    device), there is no signer.
 *
 * **TSE disabled:** nothing is signed, so rule 3 resolves to [TSE_DISABLED_CLIENT_ID] instead of a
 * problem — a closing with a count, cash payouts, a top-up or a daily Geldtransit then works from the
 * adminApp. A designated or calling till is still used (entries keep their attribution) and a
 * *deactivated* one is still refused, because [SigningDefect] checks `isActive` in every mode; the
 * other defects cannot occur when disabled.
 *
 * Call inside a transaction. DSFinV-K needs nothing extra: `DsfinvkTerminalAttribution` derives each
 * Kassenbuch Vorgang's TERMINAL_ID from the signing ClientID.
 */
object ClosingSigner {
    sealed interface Resolution {
        data class Resolved(
            val clientId: String,
            /** Null only when nothing signs (TSE disabled) and no till is involved. */
            val signerName: String?,
            /** Which rule picked [clientId] — recorded on the closing's audit row. */
            val source: Source,
        ) : Resolution

        data class Unavailable(
            val problem: ClosingSignerProblem,
            val signerName: String?,
        ) : Resolution
    }

    /**
     * The closing audit row's signer source when the closing had no cash movement to sign (no count,
     * no cash payout, no top-up, no daily Geldtransit) — no signer was resolved at all.
     */
    const val NO_SIGNER_NEEDED: String = "NONE_NO_CASH_MOVEMENTS"

    /** Which of the rules above chose the signer. */
    enum class Source {
        /** Rule 1: the till designated on the fiscal book. */
        DESIGNATED_TILL,

        /** Rule 2: nothing designated, the till the closing was triggered at. */
        CALLING_TILL,

        /** Rule 3 with the TSE disabled: no till, [TSE_DISABLED_CLIENT_ID] — nothing was signed. */
        TSE_DISABLED_PLACEHOLDER,
    }

    /** The client a closing signs under, and why that one. */
    data class Signer(
        val clientId: String,
        val source: Source,
    )

    fun resolve(
        terminalRepository: TerminalRepository,
        callerDeviceId: String,
        mode: TseMode,
        fiscalBook: CashBook,
    ): Resolution {
        val designated = fiscalBook.signingTerminal
        val signer =
            designated
                ?: callerTill(terminalRepository, callerDeviceId)
                // With the TSE disabled nothing is signed, so "no signer" is not a problem: the
                // placeholder id is never sent anywhere (see TSE_DISABLED_CLIENT_ID).
                ?: return if (mode == TseMode.DISABLED) {
                    Resolution.Resolved(clientId = TSE_DISABLED_CLIENT_ID, signerName = null, source = Source.TSE_DISABLED_PLACEHOLDER)
                } else {
                    Resolution.Unavailable(ClosingSignerProblem.NOT_DESIGNATED, signerName = null)
                }
        val name = signer.name ?: signer.clientId
        val source = if (designated != null) Source.DESIGNATED_TILL else Source.CALLING_TILL
        return when (SigningDefect.of(signer, mode)) {
            null -> Resolution.Resolved(clientId = signer.clientId, signerName = name, source = source)
            SigningDefect.INACTIVE -> Resolution.Unavailable(ClosingSignerProblem.INACTIVE, name)
            SigningDefect.NOT_FISCAL -> Resolution.Unavailable(ClosingSignerProblem.NOT_FISCAL, name)
            SigningDefect.NOT_REGISTERED -> Resolution.Unavailable(ClosingSignerProblem.NOT_REGISTERED, name)
        }
    }

    /**
     * The client to sign with and the rule that chose it, or a 409. An unfit CALLING till (rule 2)
     * keeps the refusal `requireSigningTerminal` has always given it, so existing tills see no change.
     */
    fun require(
        terminalRepository: TerminalRepository,
        callerDeviceId: String,
        mode: TseMode,
        fiscalBook: CashBook,
    ): Signer {
        if (fiscalBook.signingTerminal == null && callerTill(terminalRepository, callerDeviceId) != null) {
            return Signer(requireSigningTerminal(terminalRepository, callerDeviceId, mode).clientId, Source.CALLING_TILL)
        }
        return when (val r = resolve(terminalRepository, callerDeviceId, mode, fiscalBook)) {
            is Resolution.Resolved -> Signer(r.clientId, r.source)
            is Resolution.Unavailable -> throw refusal(r)
        }
    }

    @OptIn(TestOnlyApi::class)
    private fun refusal(r: Resolution.Unavailable): PosException =
        if (r.problem == ClosingSignerProblem.NOT_DESIGNATED) {
            PosException(
                PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED,
                HttpStatusCode.Conflict,
                "This closing books cash movements that must be TSE-signed, and no signing till is " +
                    "designated on the Ladenkasse. Designate one in the cash-book settings, or close at a till.",
            )
        } else {
            PosException(
                PosErrorCode.ZBON_SIGNER_UNAVAILABLE,
                HttpStatusCode.Conflict,
                "The Ladenkasse's signing till '${r.signerName}' cannot sign: ${unfitReason(r.problem)}.",
            )
        }

    /** The reason a designated till cannot sign, in plain words for the error detail an operator reads. */
    @TestOnlyApi
    fun unfitReason(problem: ClosingSignerProblem): String =
        when (problem) {
            ClosingSignerProblem.INACTIVE -> "it is deactivated"
            ClosingSignerProblem.NOT_FISCAL -> "it is not a fiscal till"
            ClosingSignerProblem.NOT_REGISTERED -> "it is not registered with the TSE"
            ClosingSignerProblem.NOT_DESIGNATED -> "no signing till is designated"
        }

    private fun callerTill(
        terminalRepository: TerminalRepository,
        callerDeviceId: String,
    ) = if (DeviceIdentifiers.isAdminWebDevice(callerDeviceId)) null else terminalRepository.findByDeviceId(callerDeviceId)
}
