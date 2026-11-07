/**
 * High-level ZVT client interface for payment terminal operations.
 *
 * Defines the business-level API for interacting with a payment terminal
 * via the ZVT protocol. All operations throw [PosException] on failure.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.client

import org.example.project.features.cardTerminal.zvt.models.ZvtConfig
import org.example.project.features.cardTerminal.zvt.models.ZvtDisplayInputResult
import org.example.project.features.cardTerminal.zvt.models.ZvtReadCardResult
import org.example.project.features.cardTerminal.zvt.models.ZvtTerminalStatus
import org.example.project.features.cardTerminal.zvt.models.ZvtTransactionResult
import org.example.project.infrastructure.errors.PosException

/**
 * Client interface for ZVT terminal operations.
 *
 * Provides high-level methods for all standard ZVT operations including
 * session management, payment processing, and terminal administration.
 * All methods throw [PosException] on failure.
 */
interface ZvtClient {
    /**
     * Connects to the terminal and registers the ECR session.
     *
     * Establishes a TCP connection and sends the Registration (06 00)
     * command with the specified configuration.
     *
     * @param config Terminal connection configuration
     * @return Terminal status on success
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun register(config: ZvtConfig): ZvtTerminalStatus

    /**
     * Initiates a payment authorization.
     *
     * Sends Authorization (06 01) with the specified amount.
     * Handles the full multi-step protocol: ACK → Intermediate(s) → Status-Info → Completion.
     *
     * @param amountCents Amount in minor currency units (e.g., cents)
     * @param currencyCode ISO 4217 numeric currency code (default: 978 = EUR)
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun authorize(
        amountCents: Long,
        currencyCode: Int = 978,
    ): ZvtTransactionResult

    /**
     * Reverses a completed transaction.
     *
     * Sends Reversal (06 30) with the receipt number of the original transaction.
     *
     * @param receiptNumber Receipt number of the transaction to reverse
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun reversal(receiptNumber: Int): ZvtTransactionResult

    /**
     * Issues a credit refund to a card.
     *
     * Sends Refund (06 31) with the specified amount.
     *
     * @param amountCents Refund amount in minor currency units
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun refund(amountCents: Long): ZvtTransactionResult

    /**
     * Pre-authorizes an amount on a card without completing payment.
     *
     * Sends Pre-Authorization (06 22) with the specified amount.
     *
     * @param amountCents Amount to reserve in minor currency units
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun preAuthorize(amountCents: Long): ZvtTransactionResult

    /**
     * Finalizes (books) a previously pre-authorized amount.
     *
     * Sends Book Pre-Authorization (06 24) with the receipt number and final amount.
     *
     * @param receiptNumber Receipt number of the original pre-authorization
     * @param amountCents Final amount to book (may differ from pre-auth)
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun bookPreAuthorization(
        receiptNumber: Int,
        amountCents: Long,
    ): ZvtTransactionResult

    /**
     * Performs daily settlement / end-of-day reconciliation.
     *
     * Sends End-of-Day (06 50) to trigger settlement with the payment processor.
     *
     * @return Transaction result with settlement data
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun endOfDay(): ZvtTransactionResult

    /**
     * Requests the terminal to print a totals report.
     *
     * Sends Print Totals (06 12) to trigger a print of all transactions
     * since the last End-of-Day settlement.
     *
     * @return Transaction result with totals data
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun printTotals(): ZvtTransactionResult

    /**
     * Aborts the current operation on the terminal.
     *
     * Sends Abort (06 B0). This may or may not succeed depending
     * on the terminal's current state.
     *
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun abort()

    /**
     * Polls the terminal status.
     *
     * Sends Status-Enquiry (05 01) to check the terminal's current state.
     *
     * @return Terminal status
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun statusEnquiry(): ZvtTerminalStatus

    /**
     * Runs terminal diagnostics.
     *
     * Sends Diagnosis (06 70) to perform a self-test on the terminal.
     *
     * @return Transaction result with diagnostic data
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun diagnosis(): ZvtTransactionResult

    /**
     * Ends the ECR↔PT session and disconnects.
     *
     * Sends Log-Off (06 02) and closes the TCP connection.
     *
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun logOff()

    /**
     * Resets the payment terminal hardware.
     *
     * Sends Reset Terminal (06 18). The terminal will perform a hardware
     * reset and will likely need re-registration afterwards.
     *
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun resetTerminal(): ZvtTransactionResult

    /**
     * Forces a network initialization on the terminal.
     *
     * Sends Initialization (06 93) to trigger re-registration with
     * the acquirer/payment processor network.
     *
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun initialization(): ZvtTransactionResult

    /**
     * Reads card data without initiating a payment transaction.
     *
     * Sends Read Card (06 C0) and waits for the customer to insert/tap
     * a card. Returns card details without charging.
     *
     * @param timeoutSeconds How long to wait for card insertion (default: 30)
     * @return Card data on success
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun readCard(timeoutSeconds: Int = 30): ZvtReadCardResult

    /**
     * Displays text on the payment terminal screen.
     *
     * Sends Display Text (06 E0) to show up to 8 lines of text
     * on the terminal display. No response data is expected beyond ACK.
     *
     * @param lines Text lines to display (max 8)
     * @param durationSeconds How long to show the text (0 = until keypress)
     * @param beeps Number of beeps to play
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun displayText(
        lines: List<String>,
        durationSeconds: Int = 0,
        beeps: Int = 0,
    )

    /**
     * Displays text on the terminal and prompts for numeric input.
     *
     * Sends Display Text with Input (06 E2) and waits for the customer
     * to enter digits on the terminal keypad.
     *
     * @param lines Text lines to display (max 8)
     * @param timeoutSeconds Input timeout in seconds
     * @param beeps Number of beeps
     * @param minLength Minimum digits required
     * @param maxLength Maximum digits allowed
     * @return Input result with the entered data
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun displayTextWithInput(
        lines: List<String>,
        timeoutSeconds: Int = 30,
        beeps: Int = 0,
        minLength: Int = 1,
        maxLength: Int = 16,
    ): ZvtDisplayInputResult

    /**
     * Sends turnover totals from the ECR to the terminal.
     *
     * Sends Send Turnover Totals (0F 10) during end-of-day reconciliation
     * to compare the ECR's transaction records with the terminal's records.
     *
     * @param turnoverData Raw turnover record data block
     * @return Transaction result on completion
     * @throws PosException on connection or protocol failure
     */
    @Throws(PosException::class)
    suspend fun sendTurnoverTotals(turnoverData: ByteArray): ZvtTransactionResult

    /**
     * Returns the current terminal connection status.
     */
    fun getStatus(): ZvtTerminalStatus
}
