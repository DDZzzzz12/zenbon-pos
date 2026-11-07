/**
 * ZVT client implementation handling the full protocol state machine.
 *
 * Implements the master-rights transfer protocol, ACK/NAK handling,
 * and multi-step transaction flows. Translates between the high-level
 * business API and low-level APDU exchange.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.client

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.cardTerminal.zvt.models.*
import org.example.project.features.cardTerminal.zvt.protocol.*
import org.example.project.features.cardTerminal.zvt.transport.ZvtConnectionManager
import org.example.project.features.cardTerminal.zvt.transport.ZvtTcpTransport
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory

/**
 * Implementation of [ZvtClient] using TCP transport.
 *
 * Handles the ZVT protocol state machine:
 * 1. Send command APDU → wait for ACK
 * 2. Process PT responses in a loop until Completion or Abort
 * 3. ACK each PT message
 * 4. Parse Status-Information into [ZvtTransactionResult]
 *
 * @property messageHandler Handler for dispatching async events (status, receipts)
 */
class ZvtClientImpl(
    val messageHandler: ZvtMessageHandler = ZvtMessageHandler(),
) : ZvtClient {
    private val logger = LoggerFactory.getLogger(ZvtClientImpl::class.java)

    private var connectionManager: ZvtConnectionManager? = null
    private var config: ZvtConfig = ZvtConfig()
    private var terminalStatus = ZvtTerminalStatus()

    override suspend fun register(config: ZvtConfig): ZvtTerminalStatus {
        try {
            this.config = config

            // Create transport and connection manager
            val transport = ZvtTcpTransport(config.connectionTimeoutMs.toInt())
            val connMgr = ZvtConnectionManager(transport, config)
            this.connectionManager = connMgr

            // Connect
            connMgr.connect()

            // Build Registration APDU (06 00)
            val fields = mutableMapOf<Int, ByteArray>()
            // Password (BMP 3D)
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            // Config byte (stored as single byte in a custom position — first byte of data block)
            // Currency code (BMP 49)
            fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(config.currency)

            val registrationApdu = ZvtApdu.create(ZvtCommand.REGISTRATION, fields)

            // Send and process
            val result = sendAndProcess(registrationApdu)

            terminalStatus =
                ZvtTerminalStatus(
                    connected = true,
                    registered = true,
                    terminalId = result?.terminalId,
                    busy = false,
                )

            return terminalStatus
        } catch (e: PosException) {
            terminalStatus = terminalStatus.copy(connected = false, registered = false)
            throw e
        } catch (e: Exception) {
            logger.error("Registration failed: ${e.message}", e)
            terminalStatus = terminalStatus.copy(connected = false, registered = false)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT registration failed at ${config.host}:${config.port}: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun authorize(
        amountCents: Long,
        currencyCode: Int,
    ): ZvtTransactionResult =
        executeTransaction("Authorization") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
            fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(currencyCode)
            ZvtApdu.create(ZvtCommand.AUTHORIZATION, fields)
        }

    override suspend fun reversal(receiptNumber: Int): ZvtTransactionResult =
        executeTransaction("Reversal") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.RECEIPT_NUMBER.fieldId] = ZvtBcdCodec.encode(receiptNumber.toLong(), 2)
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            ZvtApdu.create(ZvtCommand.REVERSAL, fields)
        }

    override suspend fun refund(amountCents: Long): ZvtTransactionResult =
        executeTransaction("Refund") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            ZvtApdu.create(ZvtCommand.REFUND, fields)
        }

    override suspend fun preAuthorize(amountCents: Long): ZvtTransactionResult =
        executeTransaction("Pre-Authorization") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
            fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(config.currency)
            ZvtApdu.create(ZvtCommand.PRE_AUTHORIZATION, fields)
        }

    override suspend fun bookPreAuthorization(
        receiptNumber: Int,
        amountCents: Long,
    ): ZvtTransactionResult =
        executeTransaction("Book Pre-Authorization") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.RECEIPT_NUMBER.fieldId] = ZvtBcdCodec.encode(receiptNumber.toLong(), 2)
            fields[BmpFieldDefinition.AMOUNT.fieldId] = ZvtBcdCodec.encodeAmount(amountCents)
            ZvtApdu.create(ZvtCommand.BOOK_PRE_AUTHORIZATION, fields)
        }

    override suspend fun endOfDay(): ZvtTransactionResult =
        executeTransaction("End-of-Day") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            ZvtApdu.create(ZvtCommand.END_OF_DAY, fields)
        }

    override suspend fun printTotals(): ZvtTransactionResult =
        executeTransaction("Print Totals") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            ZvtApdu.create(ZvtCommand.PRINT_TOTALS, fields)
        }

    override suspend fun abort() {
        try {
            val connMgr = connectionManager ?: throw notConnected()
            val apdu = ZvtApdu.create(ZvtCommand.ABORT)
            connMgr.send(apdu.toBytes())

            // Wait for ACK
            val responseBytes = connMgr.receive()
            val (response, _) = ZvtApdu.parse(responseBytes)
            if (response.isAck) {
                logger.info("Abort acknowledged by terminal")
            } else {
                logger.warn("Abort not acknowledged: $response")
                throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.BadGateway,
                    "Terminal did not acknowledge abort (response=$response)",
                )
            }
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Abort failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT abort failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun statusEnquiry(): ZvtTerminalStatus {
        try {
            val connMgr = connectionManager ?: throw notConnected()
            val apdu = ZvtApdu.create(ZvtCommand.STATUS_ENQUIRY)
            connMgr.send(apdu.toBytes())

            // Process response
            val result = processResponses(connMgr)

            terminalStatus =
                terminalStatus.copy(
                    connected = true,
                    busy = false,
                    terminalId = result?.terminalId ?: terminalStatus.terminalId,
                )

            return terminalStatus
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Status enquiry failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT status enquiry failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun diagnosis(): ZvtTransactionResult =
        executeTransaction("Diagnosis") {
            ZvtApdu.create(ZvtCommand.DIAGNOSIS)
        }

    override suspend fun logOff() {
        try {
            val connMgr = connectionManager
            if (connMgr != null && connMgr.isConnected) {
                val apdu = ZvtApdu.create(ZvtCommand.LOG_OFF)
                connMgr.send(apdu.toBytes())

                // Wait for ACK
                try {
                    val responseBytes = connMgr.receive(timeoutMs = 5000L)
                    val (response, _) = ZvtApdu.parse(responseBytes)
                    logger.info("Log-off response: $response")
                } catch (e: Exception) {
                    logger.warn("No response to log-off (terminal may have disconnected): ${e.message}")
                }

                connMgr.disconnect()
            }

            terminalStatus = ZvtTerminalStatus(connected = false, registered = false)
            connectionManager = null
        } catch (e: Exception) {
            logger.error("Log-off failed: ${e.message}", e)
            connectionManager?.disconnect()
            connectionManager = null
            terminalStatus = ZvtTerminalStatus(connected = false, registered = false)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT log-off failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun resetTerminal(): ZvtTransactionResult =
        executeTransaction("Reset Terminal") {
            ZvtApdu.create(ZvtCommand.RESET_TERMINAL)
        }.also {
            // Terminal will need re-registration after reset
            terminalStatus = terminalStatus.copy(registered = false)
        }

    override suspend fun initialization(): ZvtTransactionResult =
        executeTransaction("Initialization") {
            ZvtApdu.create(ZvtCommand.INITIALIZATION)
        }

    override suspend fun readCard(timeoutSeconds: Int): ZvtReadCardResult {
        try {
            val connMgr = connectionManager ?: throw notConnected()

            terminalStatus = terminalStatus.copy(busy = true)
            logger.info("Starting Read Card (timeout: ${timeoutSeconds}s)")

            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.TIMEOUT.fieldId] = byteArrayOf(timeoutSeconds.toByte())
            val apdu = ZvtApdu.create(ZvtCommand.READ_CARD, fields)

            val result = sendAndProcess(apdu, connMgr)

            terminalStatus = terminalStatus.copy(busy = false)

            return if (result != null) {
                logger.info("Read Card completed: resultCode=0x${result.resultCode.toString(16)}")
                ZvtReadCardResult(
                    success = result.resultCode == 0x00,
                    cardType = result.cardType,
                    cardTypeName = result.cardTypeName,
                    pan = result.pan,
                    expiryDate = result.expiryDate,
                    cardSequenceNumber = result.cardSequenceNumber,
                    aid = result.aid,
                    cardTechnology = result.paymentType,
                    resultCode = result.resultCode,
                    errorDescription = result.errorDescription,
                )
            } else {
                ZvtReadCardResult(
                    success = false,
                    resultCode = 0xFF,
                    errorDescription = "No status information received",
                )
            }
        } catch (e: PosException) {
            terminalStatus = terminalStatus.copy(busy = false)
            throw e
        } catch (e: Exception) {
            terminalStatus = terminalStatus.copy(busy = false)
            logger.error("Read Card failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT read card failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun displayText(
        lines: List<String>,
        durationSeconds: Int,
        beeps: Int,
    ) {
        try {
            val connMgr = connectionManager ?: throw notConnected()

            logger.info("Sending Display Text (${lines.size} lines, duration: ${durationSeconds}s)")

            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.DISPLAY_DURATION.fieldId] = byteArrayOf(durationSeconds.toByte())
            if (beeps > 0) {
                fields[BmpFieldDefinition.BEEPS.fieldId] = byteArrayOf(beeps.toByte())
            }

            val lineFields =
                listOf(
                    BmpFieldDefinition.DISPLAY_LINE_1,
                    BmpFieldDefinition.DISPLAY_LINE_2,
                    BmpFieldDefinition.DISPLAY_LINE_3,
                    BmpFieldDefinition.DISPLAY_LINE_4,
                    BmpFieldDefinition.DISPLAY_LINE_5,
                    BmpFieldDefinition.DISPLAY_LINE_6,
                    BmpFieldDefinition.DISPLAY_LINE_7,
                    BmpFieldDefinition.DISPLAY_LINE_8,
                )
            lines.take(8).forEachIndexed { index, text ->
                if (text.isNotEmpty()) {
                    fields[lineFields[index].fieldId] = text.toByteArray(Charsets.ISO_8859_1)
                }
            }

            val apdu = ZvtApdu.create(ZvtCommand.DISPLAY_TEXT, fields)
            connMgr.send(apdu.toBytes())

            // Wait for ACK
            val responseBytes = connMgr.receive()
            val (response, _) = ZvtApdu.parse(responseBytes)
            if (response.isAck) {
                logger.info("Display Text acknowledged")
            } else {
                logger.warn("Display Text not acknowledged: $response")
                throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.BadGateway,
                    "Terminal did not acknowledge Display Text (response=$response)",
                )
            }
        } catch (e: PosException) {
            throw e
        } catch (e: Exception) {
            logger.error("Display Text failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT display text failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun displayTextWithInput(
        lines: List<String>,
        timeoutSeconds: Int,
        beeps: Int,
        minLength: Int,
        maxLength: Int,
    ): ZvtDisplayInputResult {
        try {
            val connMgr = connectionManager ?: throw notConnected()

            terminalStatus = terminalStatus.copy(busy = true)
            logger.info("Sending Display Text with Input (timeout: ${timeoutSeconds}s)")

            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.DISPLAY_DURATION.fieldId] = byteArrayOf(timeoutSeconds.toByte())
            if (beeps > 0) {
                fields[BmpFieldDefinition.BEEPS.fieldId] = byteArrayOf(beeps.toByte())
            }
            fields[BmpFieldDefinition.INPUT_MIN_LENGTH.fieldId] = byteArrayOf(minLength.toByte())
            fields[BmpFieldDefinition.INPUT_MAX_LENGTH.fieldId] = byteArrayOf(maxLength.toByte())
            fields[BmpFieldDefinition.INPUT_ECHO_CHAR.fieldId] = byteArrayOf(0x2A) // '*'

            val lineFields =
                listOf(
                    BmpFieldDefinition.DISPLAY_LINE_1,
                    BmpFieldDefinition.DISPLAY_LINE_2,
                    BmpFieldDefinition.DISPLAY_LINE_3,
                    BmpFieldDefinition.DISPLAY_LINE_4,
                    BmpFieldDefinition.DISPLAY_LINE_5,
                    BmpFieldDefinition.DISPLAY_LINE_6,
                    BmpFieldDefinition.DISPLAY_LINE_7,
                    BmpFieldDefinition.DISPLAY_LINE_8,
                )
            lines.take(8).forEachIndexed { index, text ->
                if (text.isNotEmpty()) {
                    fields[lineFields[index].fieldId] = text.toByteArray(Charsets.ISO_8859_1)
                }
            }

            val apdu = ZvtApdu.create(ZvtCommand.DISPLAY_TEXT_INPUT, fields)
            val result = sendAndProcess(apdu, connMgr)

            terminalStatus = terminalStatus.copy(busy = false)

            return if (result != null) {
                // The terminal returns the input via Status Information
                // Additional text (BMP 3C) or TLV tag contains the entered data
                val inputData = result.aid ?: "" // Input data may come in various BMP fields
                ZvtDisplayInputResult(
                    success = result.resultCode == 0x00,
                    inputData = inputData,
                    resultCode = result.resultCode,
                    errorDescription = result.errorDescription,
                )
            } else {
                ZvtDisplayInputResult(
                    success = false,
                    resultCode = 0xFF,
                    errorDescription = "No response received",
                )
            }
        } catch (e: PosException) {
            terminalStatus = terminalStatus.copy(busy = false)
            throw e
        } catch (e: Exception) {
            terminalStatus = terminalStatus.copy(busy = false)
            logger.error("Display Text with Input failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT display text with input failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun sendTurnoverTotals(turnoverData: ByteArray): ZvtTransactionResult =
        executeTransaction("Send Turnover Totals") {
            val fields = mutableMapOf<Int, ByteArray>()
            fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)
            fields[BmpFieldDefinition.TURNOVER_RECORD.fieldId] = turnoverData
            ZvtApdu.create(ZvtCommand.SEND_TURNOVER_TOTALS, fields)
        }

    override fun getStatus(): ZvtTerminalStatus = terminalStatus

    // ===== Internal Protocol Engine =====

    /**
     * Executes a transaction: build APDU → send → process responses → return result.
     *
     * @param operationName Name for logging
     * @param buildApdu Lambda to construct the command APDU
     * @return Transaction result
     * @throws PosException on connection or protocol failure
     */
    private suspend fun executeTransaction(
        operationName: String,
        buildApdu: () -> ZvtApdu,
    ): ZvtTransactionResult {
        try {
            val connMgr = connectionManager ?: throw notConnected()

            terminalStatus = terminalStatus.copy(busy = true)
            logger.info("Starting $operationName")

            val result = sendAndProcess(buildApdu(), connMgr)

            terminalStatus = terminalStatus.copy(busy = false)

            return if (result != null) {
                logger.info("$operationName completed: approved=${result.approved}, rc=0x${result.resultCode.toString(16)}")
                result
            } else {
                logger.warn("$operationName completed without status information")
                ZvtTransactionResult(
                    approved = false,
                    resultCode = 0xFF,
                    errorDescription = "No status information received",
                )
            }
        } catch (e: PosException) {
            terminalStatus = terminalStatus.copy(busy = false)
            throw e
        } catch (e: Exception) {
            terminalStatus = terminalStatus.copy(busy = false)
            logger.error("$operationName failed: ${e.message}", e)
            throw PosException(
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "ZVT $operationName failed: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * Sends an APDU and processes the full response cycle.
     *
     * Protocol flow:
     * 1. Send command APDU
     * 2. Wait for ACK from PT
     * 3. Enter response loop: process PT messages until Completion/Abort
     * 4. ACK each PT message
     *
     * @param apdu The command APDU to send
     * @param connMgr Connection manager (defaults to instance field)
     * @return Parsed transaction result, or null if no Status-Information received
     */
    private suspend fun sendAndProcess(
        apdu: ZvtApdu,
        connMgr: ZvtConnectionManager = this.connectionManager!!,
    ): ZvtTransactionResult? {
        // Step 1: Send command
        logger.debug("Sending: $apdu")
        connMgr.send(apdu.toBytes())

        // Step 2: Wait for ACK
        val ackBytes = connMgr.receive()
        val (ackApdu, _) = ZvtApdu.parse(ackBytes)

        if (ackApdu.isNak) {
            val errorCode = ackApdu.nakErrorCode ?: 0xFF
            logger.warn("NAK received: error code 0x${errorCode.toString(16)}")
            return ZvtTransactionResult(
                approved = false,
                resultCode = errorCode,
                errorDescription = ZvtErrorCode.descriptionFor(errorCode),
            )
        }

        if (!ackApdu.isAck) {
            logger.warn("Expected ACK but got: $ackApdu")
        }

        // Step 3: Process PT responses
        return processResponses(connMgr)
    }

    /**
     * Processes PT response messages until Completion or Abort.
     *
     * Handles:
     * - Status Information (04 0F): parses transaction result
     * - Intermediate Status (04 FF): dispatches to message handler
     * - Print Line (06 D1): collects receipt lines
     * - Print Text-Block (06 D3): collects receipt blocks
     * - Completion (06 0F): signals end, returns master-rights
     * - PT Abort (06 1E): signals error, returns master-rights
     *
     * @return The parsed transaction result, or null if none received
     */
    private suspend fun processResponses(connMgr: ZvtConnectionManager): ZvtTransactionResult? {
        var result: ZvtTransactionResult? = null
        val receiptLines = mutableListOf<String>()

        while (true) {
            val responseBytes = connMgr.receive()
            val (response, _) = ZvtApdu.parse(responseBytes)
            logger.debug("Received: $response")

            when {
                response.isStatusInformation -> {
                    result = parseStatusInformation(response)
                    // Send ACK
                    connMgr.send(ZvtApdu.ack().toBytes())
                }

                response.isCompletion -> {
                    logger.debug("Completion received — master-rights returned to ECR")
                    connMgr.send(ZvtApdu.ack().toBytes())
                    break
                }

                response.isPtAbort -> {
                    logger.warn("PT Abort received")
                    connMgr.send(ZvtApdu.ack().toBytes())
                    if (result == null) {
                        val errorCode = ZvtBmpField.getResultCode(response.bmpFields) ?: 0xFF
                        result =
                            ZvtTransactionResult(
                                approved = false,
                                resultCode = errorCode,
                                errorDescription = ZvtErrorCode.descriptionFor(errorCode),
                            )
                    }
                    break
                }

                response.isIntermediateStatus -> {
                    val statusCode =
                        if (response.dataBlock.isNotEmpty()) {
                            response.dataBlock[0].toInt() and 0xFF
                        } else {
                            0x06
                        }
                    messageHandler.onIntermediateStatus(statusCode)
                    connMgr.send(ZvtApdu.ack().toBytes())
                }

                response.isPrintLine -> {
                    val text = extractPrintLineText(response)
                    if (text != null) {
                        receiptLines.add(text)
                        messageHandler.onReceiptLine(text)
                    }
                    connMgr.send(ZvtApdu.ack().toBytes())
                }

                response.isPrintTextBlock -> {
                    val receiptData = extractPrintTextBlock(response)
                    receiptLines.addAll(receiptData.lines)
                    messageHandler.onPrintTextBlock(receiptData)
                    connMgr.send(ZvtApdu.ack().toBytes())
                }

                response.isAck -> {
                    // ACK to our ACK — skip
                    logger.debug("ACK received (echo)")
                }

                else -> {
                    logger.warn("Unexpected response: $response — sending ACK")
                    connMgr.send(ZvtApdu.ack().toBytes())
                }
            }
        }

        // Attach receipt lines to result if available
        if (result != null && receiptLines.isNotEmpty()) {
            result = result.copy(receiptText = receiptLines)
        }

        return result
    }

    /**
     * Parses a Status Information (04 0F) APDU into a [ZvtTransactionResult].
     */
    private fun parseStatusInformation(apdu: ZvtApdu): ZvtTransactionResult {
        val fields = apdu.bmpFields
        val resultCode = ZvtBmpField.getResultCode(fields) ?: 0x00
        val approved = resultCode == 0x00

        // Parse date and time if present
        val dateField = fields[BmpFieldDefinition.DATE.fieldId]
        val timeField = fields[BmpFieldDefinition.TIME.fieldId]
        val dateStr =
            dateField?.let {
                val (month, day) = ZvtBcdCodec.decodeDate(it)
                "${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}"
            }
        val timeStr =
            timeField?.let {
                val (h, m, s) = ZvtBcdCodec.decodeTime(it)
                "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            }

        // Parse expiry date if present
        val expiryField = fields[BmpFieldDefinition.EXPIRY_DATE.fieldId]
        val expiryStr = expiryField?.let { ZvtBcdCodec.decodeDigitString(it) }

        // Parse card sequence number
        val cardSeqField = fields[BmpFieldDefinition.CARD_SEQUENCE_NUMBER.fieldId]
        val cardSeqStr = cardSeqField?.let { ZvtBcdCodec.decodeDigitString(it) }

        // Parse AID
        val aidField = fields[BmpFieldDefinition.AID.fieldId]
        val aidStr = aidField?.let { String(it, Charsets.ISO_8859_1).trim() }

        val cardTypeId = ZvtBmpField.getCardType(fields)

        return ZvtTransactionResult(
            approved = approved,
            resultCode = resultCode,
            errorDescription = ZvtErrorCode.descriptionFor(resultCode),
            amountCents = ZvtBmpField.getAmount(fields) ?: 0,
            receiptNumber = ZvtBmpField.getReceiptNumber(fields),
            traceNumber = ZvtBmpField.getTraceNumber(fields),
            terminalId = ZvtBmpField.getTerminalId(fields),
            cardType = cardTypeId,
            cardTypeName = cardTypeId?.let { ZvtCardType.displayNameFor(it) },
            pan = ZvtBmpField.getPan(fields),
            expiryDate = expiryStr,
            paymentType = fields[BmpFieldDefinition.PAYMENT_TYPE.fieldId]?.get(0)?.toInt()?.and(0xFF),
            currencyCode = ZvtBmpField.getCurrencyCode(fields),
            date = dateStr,
            time = timeStr,
            aid = aidStr,
            cardSequenceNumber = cardSeqStr,
        )
    }

    /**
     * Extracts text from a Print Line (06 D1) APDU.
     */
    private fun extractPrintLineText(apdu: ZvtApdu): String? {
        if (apdu.dataBlock.isEmpty()) return null
        // Print Line data block contains the text as raw bytes
        return String(apdu.dataBlock, Charsets.ISO_8859_1).trim()
    }

    /**
     * Extracts receipt data from a Print Text-Block (06 D3) APDU.
     */
    private fun extractPrintTextBlock(apdu: ZvtApdu): ZvtReceiptData {
        if (apdu.dataBlock.isEmpty()) return ZvtReceiptData()

        // Print Text-Block may contain TLV-encoded receipt data
        // or plain text separated by line breaks
        val text = String(apdu.dataBlock, Charsets.ISO_8859_1)
        val lines =
            text
                .split("\n", "\r\n", "\r")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        return ZvtReceiptData(lines = lines)
    }

    private fun notConnected(): PosException =
        PosException(
            PosErrorCode.CARD_TERMINAL_DISCONNECTED,
            HttpStatusCode.ServiceUnavailable,
            "ZVT terminal not connected (host=${config.host}:${config.port})",
        )
}
