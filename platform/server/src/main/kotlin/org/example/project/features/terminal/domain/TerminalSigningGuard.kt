package org.example.project.features.terminal.domain

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.DeviceIdentifiers
import org.example.project.features.terminal.data.service.TransactionSigningGuard
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.infrastructure.errors.PosException

/**
 * Resolves the registered POS terminal for a fiscal (TSE-signing) operation, **and asserts it may
 * actually sign**.
 *
 * TSE signing requires a real terminal and its `clientId`. This guard is the single choke point
 * every signing path (orders, bills, Z-Bon, Kassenbuch) uses to turn a request's device id into a
 * terminal:
 *
 * - The wasm admin console's synthetic `admin-web-*` id can never be a terminal (the
 *   terminal-register endpoint rejects it too), so it is refused with 403 before any signing is
 *   attempted.
 * - Every other device id — including the `"unknown"` login fallback used by clients that send no
 *   device header — defers to the terminal lookup, which yields the terminal when one is registered
 *   and 404 `CARD_TERMINAL_NOT_FOUND` otherwise (with the same "not a registered POS terminal"
 *   wording). This keeps existing single-till setups working unchanged.
 * - The resolved terminal is then put through [TransactionSigningGuard.assertMaySign], which is
 *   where `isActive` / `fiscal` / `tseClientRegistered` are enforced.
 *
 * ### Why the two guards were unified (Stage 4b, parent spec §5.4)
 *
 * They used to be separate, and of **unequal strength**: this function checked only that the
 * terminal existed and was not `admin-web-*`, while `assertMaySign` checked whether it was actually
 * fit to sign. Order, OrderItem, Bill and SalesInvoice called both. Z-Bon
 * (`ZBonServiceImpl`), CashBookTransfer and Kassenbuch called **only this one** — yet all three
 * signed under the calling terminal's own `clientId`, and `TransactionalRoutes` deliberately
 * excludes their routes, so the route-level ceiling did not backstop them either.
 *
 * A deactivated, non-fiscal or TSE-unregistered terminal could therefore produce a TSE-signed Z-Bon
 * or fiscal Kassenbuch entry, while the same terminal was refused an ordinary order or bill.
 *
 * `TransactionalRoutes`' KDoc justified excluding those routes on the grounds that they are
 * "register-level closing/reporting documents — not per-terminal sales transactions attributed to
 * the calling station's own TSE client". The three call sites above contradicted that: they *are*
 * attributed to the calling station's own client. The rationale was stale, not the code's premise.
 *
 * **Taking `mode` is what makes the fix structural rather than a patch.** Three call sites could
 * have had `assertMaySign` added by hand, and the next signing path added would have had the same
 * even chance of forgetting it. A required parameter cannot be forgotten: there is no longer a
 * weaker guard to reach for.
 *
 * Must be called inside an ambient `dbQuery` transaction (it calls
 * [TerminalRepository.findByDeviceId]).
 *
 * @param mode the shop's current TSE mode, from `IShopConfigurationManager.getTseMode()`. Resolve it
 *   *before* creating anything the guard's rejection would have to roll back — see
 *   `OrderServiceImpl.createOrder`, which hoists both to the top for exactly that reason.
 */
fun requireSigningTerminal(
    terminalRepository: TerminalRepository,
    deviceId: String,
    mode: TseMode,
): Terminal {
    if (DeviceIdentifiers.isAdminWebDevice(deviceId)) {
        throw PosException(PosErrorCode.AUTH_FORBIDDEN, HttpStatusCode.Forbidden, notATillDetail(deviceId))
    }
    val terminal =
        terminalRepository.findByDeviceId(deviceId)
            ?: throw PosException(
                PosErrorCode.CARD_TERMINAL_NOT_FOUND,
                HttpStatusCode.NotFound,
                notATillDetail(deviceId),
            )
    TransactionSigningGuard.assertMaySign(terminal, mode)
    return terminal
}

/**
 * The stand-in TSE client id used when the TSE mode is [TseMode.DISABLED] and the device that
 * triggered a Kassenbuch-family operation is not a registered till (the adminApp's `admin-web-*`, an
 * unknown device). **It is never sent to a TSE and never persisted**: in DISABLED mode
 * `DisabledTseTransactionServiceImpl.signTransaction` ignores the client id and returns null, the
 * outcome resolver stamps the entry `DISABLED` and decides no queueing, so neither a signing-queue row
 * nor any foreign key on a client id is created from it. It exists only because the signing call
 * signatures want a non-null string. (A Z-Bon closing's `CREATE_REPORT` audit row does name it, as the
 * marker that nothing was signed — audit text, not fiscal data.)
 */
const val TSE_DISABLED_CLIENT_ID: String = "TSE-DISABLED"

/**
 * The TSE client id a Kassenbuch-family operation (manual Kassenbuch entry or correction, cash-book
 * transfer leg, Z-Bon closing movement) signs under — the variant of [requireSigningTerminal] for
 * callers that need only the id **string**, never a `Terminal` entity.
 *
 * - **NORMAL / SIMULATION:** exactly `requireSigningTerminal(...).clientId` — admin-web is refused
 *   with 403, an unregistered device with 404, an unfit till by `assertMaySign`. Nothing is weakened.
 * - **DISABLED:** nothing is signed, so a device that is no registered till (admin-web, unknown) is
 *   allowed and gets [TSE_DISABLED_CLIENT_ID]. A registered till still goes through `assertMaySign`
 *   (a deactivated till is refused in every mode) and its own client id is returned, so entries stay
 *   attributed to it as in the signed case.
 *
 * Callers that persist a `Terminal` reference or read terminal fields (orders, order items, bills,
 * sales-invoice clearing bills — `bill.terminal` / `order.terminal`, DSFinV-K attribution) must keep
 * using [requireSigningTerminal]: they cannot work without a terminal row.
 */
fun resolveSigningClientId(
    terminalRepository: TerminalRepository,
    deviceId: String,
    mode: TseMode,
): String {
    if (mode != TseMode.DISABLED) return requireSigningTerminal(terminalRepository, deviceId, mode).clientId
    val till =
        if (DeviceIdentifiers.isAdminWebDevice(deviceId)) null else terminalRepository.findByDeviceId(deviceId)
    if (till == null) return TSE_DISABLED_CLIENT_ID
    TransactionSigningGuard.assertMaySign(till, mode)
    return till.clientId
}

/**
 * The refusal text for a device that cannot sign because it is no registered till — the same words
 * for the admin console and for any unregistered device id (a bare "Terminal not found" read like a
 * missing record, not like "this device is not a till"). The codes stay as they were; clients and
 * tests key on them.
 */
private fun notATillDetail(deviceId: String): String = "Client '$deviceId' is not a registered POS terminal and cannot perform fiscal (TSE) operations"
