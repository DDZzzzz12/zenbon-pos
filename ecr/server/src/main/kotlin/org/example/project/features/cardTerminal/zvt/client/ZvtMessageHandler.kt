/**
 * Async message dispatch handler for ZVT PT→ECR messages.
 *
 * Manages SharedFlow-based event streams for intermediate status updates,
 * receipt data, and print requests received from the payment terminal
 * during transaction processing.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.client

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.example.project.features.cardTerminal.zvt.models.ZvtIntermediateStatus
import org.example.project.features.cardTerminal.zvt.models.ZvtReceiptData
import org.slf4j.LoggerFactory

/**
 * Handles asynchronous message dispatch from PT responses.
 *
 * Provides [SharedFlow]s that consumers (UI, receipt printer, etc.)
 * can collect to receive real-time updates during ZVT transactions.
 */
class ZvtMessageHandler {
    private val logger = LoggerFactory.getLogger(ZvtMessageHandler::class.java)

    private val _intermediateStatusFlow = MutableSharedFlow<ZvtIntermediateStatus>(extraBufferCapacity = 10)
    private val _receiptFlow = MutableSharedFlow<ZvtReceiptData>(extraBufferCapacity = 10)
    private val _printTextBlockFlow = MutableSharedFlow<ZvtReceiptData>(extraBufferCapacity = 5)

    /**
     * Flow of intermediate status updates from the terminal.
     *
     * Emits status codes during transaction processing (e.g., "Please insert card",
     * "PIN entry", "Connecting to host").
     */
    val intermediateStatusFlow: SharedFlow<ZvtIntermediateStatus> = _intermediateStatusFlow.asSharedFlow()

    /**
     * Flow of individual receipt lines from Print Line (06 D1) messages.
     */
    val receiptFlow: SharedFlow<ZvtReceiptData> = _receiptFlow.asSharedFlow()

    /**
     * Flow of complete receipt text blocks from Print Text-Block (06 D3) messages.
     */
    val printTextBlockFlow: SharedFlow<ZvtReceiptData> = _printTextBlockFlow.asSharedFlow()

    /**
     * Dispatches an intermediate status update.
     *
     * Called by the client when an Intermediate Status (04 FF) APDU is received.
     *
     * @param statusCode The status code from the APDU data block
     */
    suspend fun onIntermediateStatus(statusCode: Int) {
        val status = ZvtIntermediateStatus.fromCode(statusCode) ?: ZvtIntermediateStatus.PLEASE_WAIT
        logger.debug("Intermediate status: ${status.description} (0x${statusCode.toString(16).padStart(2, '0')})")
        _intermediateStatusFlow.emit(status)
    }

    /**
     * Dispatches a receipt line.
     *
     * Called by the client when a Print Line (06 D1) APDU is received.
     *
     * @param line The receipt text line
     */
    suspend fun onReceiptLine(line: String) {
        logger.debug("Receipt line: $line")
        _receiptFlow.emit(ZvtReceiptData(lines = listOf(line)))
    }

    /**
     * Dispatches a complete receipt text block.
     *
     * Called by the client when a Print Text-Block (06 D3) APDU is received.
     *
     * @param receiptData The parsed receipt data
     */
    suspend fun onPrintTextBlock(receiptData: ZvtReceiptData) {
        logger.debug("Print text block: ${receiptData.lines.size} lines")
        _printTextBlockFlow.emit(receiptData)
    }
}
