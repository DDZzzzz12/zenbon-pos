package org.example.project.tse.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Golden test for the TR-03153 log-message parser, anchored on REAL bytes exported off a
 * Swissbit TSE 1.x (SW 1.1.0, unixTime), plus a correctly-shaped synthetic transaction log for
 * the fields the real export happened not to contain (it was all system logs).
 *
 * The confirmed TR-03151 LogMessage layout:
 *
 *   SEQUENCE {
 *     version            INTEGER
 *     certifiedDataType  OID
 *     certifiedData      -- IMPLICIT context tags [0..n]; meaning depends on certifiedDataType
 *     serialNumber       OCTET STRING
 *     signatureAlgorithm SEQUENCE { algorithm OID, parameters? }   -- NOT a bare OID
 *     signatureCounter   INTEGER
 *     logTime            INTEGER (unixTime) | UTCTime | GeneralizedTime
 *     signatureValue     OCTET STRING
 *   }
 */
class Tr03153LogMessageParserTest {

    // A real system-log entry (id=10): AuthenticateUser, logged during commissioning before the
    // clock was set (hence logTime = 13). certifiedDataType 0.4.0.127.0.7.3.7.1.2.
    private val realSystemLogAuthenticate =
        "3081c8020102060904007f00070307010280104175746865" +
            "6e74696361746555736572810d810561646d696e820100830100" +
            "04204ab62b2f49710d4d27af38f74ae416187db8741c630516bac39cad5fdf67905c" +
            "300c060a04007f000701010401040202064102010d" +
            "046076ef159ae42f8e71215012a5709f8d24086049ba98150164e2ac54e6f7b3e6ed" +
            "1efb6b27b75b5cf9e79bcd7f8de7da7970ef9610099fab2e73823ab4ab8414181" +
            "35e77bf40f5c8afd3bcf60601de29b63332a2244598c0b74a3912f8e06c302f"

    // A real system-log entry (id=26): UpdateTime, after the clock was set — logTime is now a
    // real unix epoch, and the signature counter has advanced.
    private val realSystemLogUpdateTime =
        "3081c3020102060904007f000703070102800a55706461746554696d65" +
            "810b8103009aa782046a3cf01a" +
            "04204ab62b2f49710d4d27af38f74ae416187db8741c630516bac39cad5fdf67905c" +
            "300c060a04007f000701010401040202064902046a3cf01a" +
            "04602278452ca865f9b798104c6b6d073359758f136f896bfec47cdba3f669784ac2" +
            "4a3210452f5444fc7eb60322956971783f90670aa4a0d111156671108fecb929d70a" +
            "9de53868611d2e4bb88d6f60da5816d0b1c0c1e752d807f96728ba4b163b"

    @Test
    fun `decodes a real system-log message end to end`() {
        val p = Tr03153LogMessageParser.parse(hex(realSystemLogAuthenticate))!!

        assertEquals(2, p.version)
        assertEquals("AuthenticateUser", p.operationType)
        assertEquals(
            "4ab62b2f49710d4d27af38f74ae416187db8741c630516bac39cad5fdf67905c",
            p.serialNumberHex,
            "the 32-byte serial — which the old parser mistakenly returned as the signature",
        )
        assertEquals(
            "0.4.0.127.0.7.1.1.4.1.4",
            p.signatureAlgorithm,
            "the OID inside the AlgorithmIdentifier SEQUENCE, not a bare OID",
        )
        assertEquals(1601L, p.signatureCounter)
        assertEquals(13L, p.logTime, "clock not yet set at this point in commissioning")
        assertEquals(
            "76ef159ae42f8e71215012a5709f8d24086049ba98150164e2ac54e6f7b3e6ed" +
                "1efb6b27b75b5cf9e79bcd7f8de7da7970ef9610099fab2e73823ab4ab8414181" +
                "35e77bf40f5c8afd3bcf60601de29b63332a2244598c0b74a3912f8e06c302f",
            p.signatureHex,
            "the real 96-byte ECDSA signature (the LAST octet string), not the serial",
        )
        // A system log carries no client / process / transaction fields.
        assertNull(p.clientId)
        assertNull(p.processType)
        assertNull(p.transactionNumber)
        // v3-only fields stay null on a v2 (TSE 1.x) system log, even though it carries [1]/[2]/[3]
        // context tags — the version guard keeps their v2 meaning (systemOperationData, …) unread.
        assertNull(p.eventOrigin)
        assertNull(p.eventTriggeredByUser)
        assertNull(p.eventDataHex)
        assertNull(p.authenticationResult)
        assertNull(p.remainingRetries)
        assertNull(p.authRole)
    }

    @Test
    fun `decodes a real unix logTime once the clock is set`() {
        val p = Tr03153LogMessageParser.parse(hex(realSystemLogUpdateTime))!!

        assertEquals("UpdateTime", p.operationType)
        assertEquals(1609L, p.signatureCounter, "the counter advances by one per entry")
        assertEquals(1782378522L, p.logTime, "a genuine unix timestamp, decoded as a Long")
    }

    // A real SE-audit log (id=60). Its shape differs from transaction/system: no context tags,
    // and an extra 192-byte seAuditData OCTET STRING sits between signatureAlgorithm and the
    // signatureCounter — which the old parser mistook for the signature, leaving 108 bytes over.
    private val realAuditLog =
        "3082016d020102060904007f00070307010304204ab62b2f49710d4d27af38f7" +
            "4ae416187db8741c630516bac39cad5fdf67905c300c060a04007f0007010104" +
            "01040481c0a01e8008000000006a42cbac81010282028c228301008408000000" +
            "006a42cb9ba01e8008000000000000000e81010282028c228301008408000000" +
            "006a63dab6a01e80080000000000009aa781010282028c228301008408000000" +
            "006a3cf01aa01e8008000000006a3cf4db81010282028c228301008408000000" +
            "006a3cf4cba01e8008000000006a3cf98c81010282028c228301008408000000" +
            "006a3cf97ba01e8008000000006a3d099281010282028c228301008408000000" +
            "006a3d09510202065a02046a3d0e55046075d100207736e8e07bbf7b1e42ecf1" +
            "ae7a507f1972c139e6648735ed394ad3bb62a4baeec503713af2cb7e62ed2856" +
            "6685194908f78e81efd6ae1d3c6411d89a44ff44f3f00b7edc878cf32d32ed89" +
            "ed33d2138a9c6e3bba3566a7c882e568f6"

    @Test
    fun `decodes an audit log, skipping the seAuditData that precedes the counter`() {
        val p = Tr03153LogMessageParser.parse(hex(realAuditLog))!!

        assertEquals(2, p.version)
        assertNull(p.operationType, "an audit log has no operation type")
        assertEquals(
            "4ab62b2f49710d4d27af38f74ae416187db8741c630516bac39cad5fdf67905c",
            p.serialNumberHex,
        )
        assertEquals(1626L, p.signatureCounter, "the counter — not the 192-byte seAuditData")
        assertEquals(1782386261L, p.logTime)
        assertEquals(
            "75d100207736e8e07bbf7b1e42ecf1ae7a507f1972c139e6648735ed394ad3bb" +
                "62a4baeec503713af2cb7e62ed28566685194908f78e81efd6ae1d3c6411d89a4" +
                "4ff44f3f00b7edc878cf32d32ed89ed33d2138a9c6e3bba3566a7c882e568f6",
            p.signatureHex,
            "the real 96-byte signature at the very end, not the seAuditData",
        )
    }

    @Test
    fun `decodes a transaction log's client, process and transaction fields`() {
        // The real export was all system logs; this is the transaction envelope built to spec
        // (certifiedDataType 0.4.0.127.0.7.3.7.1.1), exercising the [1]/[3]/[5] context fields.
        val p = Tr03153LogMessageParser.parse(buildTransactionLogMessage())!!

        assertEquals(2, p.version)
        assertEquals("StartTransaction", p.operationType)
        assertEquals("TER-001", p.clientId)
        assertEquals("Kassenbeleg-V1", p.processType)
        assertEquals(42L, p.transactionNumber)
        assertEquals(1234L, p.signatureCounter)
        assertEquals(1_784_000_000L, p.logTime)
        assertEquals("0.4.0.127.0.7.1.1.4.1.3", p.signatureAlgorithm)
    }

    @Test
    fun `decodes a version 3 transaction log (TSE 2_0) with the same field mapping`() {
        // TR-03151-1 §2.2 / TR-03153-1 Table 7: on TSE 2.0 the LogMessage version field is 3,
        // but the transaction certifiedData keeps the [0]/[1]/[2]/[3]/[5] layout — same walk.
        val p = Tr03153LogMessageParser.parse(buildV3TransactionLogMessage())!!

        assertEquals(3, p.version, "TSE 2.0 log messages carry version = 3")
        assertEquals("FinishTransaction", p.operationType)
        assertEquals("TER-2", p.clientId)
        assertEquals("Kassenbeleg-V1", p.processType)
        assertEquals(99L, p.transactionNumber)
        assertEquals(5000L, p.signatureCounter)
        assertEquals(1_784_500_000L, p.logTime)
        assertEquals("0.4.0.127.0.7.1.1.4.1.3", p.signatureAlgorithm)
        assertNull(p.eventOrigin, "eventOrigin is a system-log field; absent on a transaction log")
    }

    @Test
    fun `version 3 system log's nested eventData integers do not leak into counter or time`() {
        // TR-03151-1 §2.2.1: a v3 SystemLogMessage carries eventData as context tag [3], an
        // IMPLICIT SEQUENCE. For the new SMAERS events (TR-03153-1 §9.8) that SEQUENCE holds its
        // own INTEGERs (e.g. remainingRetries) — the audit-seAuditData trap in a new place. If the
        // parser ever stopped consuming [3] wholesale, remainingRetries=3 would surface as the
        // signature counter. It must not.
        val p = Tr03153LogMessageParser.parse(buildV3SystemLogAuthenticateSmaAdmin())!!

        assertEquals(3, p.version)
        assertEquals("authenticateSmaAdmin", p.operationType, "read from eventType [0]")
        assertEquals(8888L, p.signatureCounter, "the real counter — NOT the nested remainingRetries=3")
        assertEquals(1_784_600_000L, p.logTime, "the real signatureCreationTime — NOT a nested integer")
        assertEquals(
            "44".repeat(96),
            p.signatureHex,
            "the trailing 96-byte signatureValue, not the serial or the eventData",
        )
        // The v3-only system fields are now surfaced (previously skipped).
        assertEquals("SMA", p.eventOrigin)
        assertEquals("smaadmin", p.eventTriggeredByUser)
        assertEquals(
            "130561646d696e0101ff020103",
            p.eventDataHex,
            "eventData [3] contents: role 'admin' + authResult TRUE + remainingRetries 3",
        )
        // Structured decode of the SMAERS authenticateSmaAdmin eventData.
        assertEquals("success", p.authenticationResult, "BOOLEAN TRUE -> success")
        assertEquals(3L, p.remainingRetries)
        assertEquals("admin", p.authRole)
        // A system log carries no transaction fields.
        assertNull(p.clientId)
        assertNull(p.processType)
        assertNull(p.transactionNumber)
    }

    @Test
    fun `decodes a v3 authenticateUser event, mapping the ENUMERATED result and the correct role`() {
        // authenticateUser's eventData is {userId, role, authenticationResult ENUMERATED,
        // remainingRetries}: the role is the SECOND string (not the userId), and the result is an
        // enum, not a boolean. Both nested integers (enum 2, retries 2) must stay out of the
        // counter/time.
        val p = Tr03153LogMessageParser.parse(buildV3SystemLogAuthenticateUser())!!

        assertEquals(3, p.version)
        assertEquals("authenticateUser", p.operationType)
        assertEquals("SMA", p.eventOrigin)
        assertEquals("TSE-Kasse-1", p.eventTriggeredByUser)
        assertEquals("incorrectPin", p.authenticationResult, "ENUMERATED 2 -> incorrectPin")
        assertEquals(2L, p.remainingRetries)
        assertEquals("cashier", p.authRole, "the role is the 2nd string, not the userId")
        assertEquals(9999L, p.signatureCounter, "not the enum value or the retry count")
        assertEquals(1_784_700_000L, p.logTime)
    }

    // --- minimal DER writer -------------------------------------------------------------

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + len(value.size) + value

    private fun int(v: Long): ByteArray {
        if (v == 0L) return tlv(0x02, byteArrayOf(0))
        var x = v
        val out = ArrayList<Byte>()
        while (x > 0) { out.add(0, (x and 0xFF).toByte()); x = x shr 8 }
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0) // keep positive
        return tlv(0x02, out.toByteArray())
    }

    private fun int(v: Int) = int(v.toLong())

    /** OID 0.4.0.127.0.7.3.7.1.1 — id-SE-API-transaction-log */
    private fun oidTransactionLog(): ByteArray =
        tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x01))

    /** OID 0.4.0.127.0.7.1.1.4.1.3 — ecdsa-plain-SHA384 */
    private fun oidEcdsaSha384(): ByteArray =
        tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x01, 0x01, 0x04, 0x01, 0x03))

    private fun buildTransactionLogMessage(): ByteArray {
        val body =
            int(2) +                                                     // version
                oidTransactionLog() +                                    // certifiedDataType
                tlv(0x80, "StartTransaction".toByteArray()) +            // [0] operationType
                tlv(0x81, "TER-001".toByteArray()) +                     // [1] clientId
                tlv(0x82, byteArrayOf(0xDE.toByte(), 0xAD.toByte())) +   // [2] processData
                tlv(0x83, "Kassenbeleg-V1".toByteArray()) +              // [3] processType
                tlv(0x85, byteArrayOf(0x2A)) +                           // [5] transactionNumber = 42
                tlv(0x04, ByteArray(32) { 0xAB.toByte() }) +             // serialNumber
                tlv(0x30, oidEcdsaSha384()) +                            // signatureAlgorithm SEQUENCE
                int(1234) +                                              // signatureCounter
                int(1_784_000_000) +                                     // logTime (unixTime)
                tlv(0x04, ByteArray(96) { 0xCD.toByte() })               // signatureValue
        return tlv(0x30, body)
    }

    /** OID 0.4.0.127.0.7.3.7.1.2 — id-SE-API-system-log */
    private fun oidSystemLog(): ByteArray =
        tlv(0x06, byteArrayOf(0x04, 0x00, 0x7F, 0x00, 0x07, 0x03, 0x07, 0x01, 0x02))

    private fun bool(v: Boolean): ByteArray =
        tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0x00))

    /** A version-3 transaction LogMessage (TSE 2.0), certifiedDataType id-SE-API-transaction-log. */
    private fun buildV3TransactionLogMessage(): ByteArray {
        val body =
            int(3) +                                                     // version = 3 (TSE 2.0)
                oidTransactionLog() +                                    // certifiedDataType
                tlv(0x80, "FinishTransaction".toByteArray()) +           // [0] operationType
                tlv(0x81, "TER-2".toByteArray()) +                       // [1] clientId
                tlv(0x82, byteArrayOf(0xBE.toByte(), 0xEF.toByte())) +   // [2] processData
                tlv(0x83, "Kassenbeleg-V1".toByteArray()) +              // [3] processType
                tlv(0x85, byteArrayOf(0x63)) +                           // [5] transactionNumber = 99
                tlv(0x04, ByteArray(32) { 0x11 }) +                      // serialNumber
                tlv(0x30, oidEcdsaSha384()) +                            // signatureAlgorithm SEQUENCE
                int(5000) +                                              // signatureCounter
                int(1_784_500_000) +                                     // signatureCreationTime (unixTime)
                tlv(0x04, ByteArray(96) { 0x22 })                        // signatureValue
        return tlv(0x30, body)
    }

    /**
     * A version-3 system LogMessage for the SMAERS event authenticateSmaAdmin (TR-03153-1 §9.8.1),
     * whose eventData [3] is an IMPLICIT SEQUENCE { role, authenticationResult, remainingRetries }.
     * remainingRetries is deliberately a small INTEGER that would corrupt the counter if [3] leaked.
     */
    private fun buildV3SystemLogAuthenticateSmaAdmin(): ByteArray {
        val eventData =
            tlv(
                0xA3,                                                    // [3] IMPLICIT (constructed) eventData
                tlv(0x13, "admin".toByteArray()) +                       // role PrintableString
                    bool(true) +                                         // authenticationResult = TRUE
                    int(3),                                              // remainingRetries = 3 (the trap)
            )
        val body =
            int(3) +                                                     // version = 3
                oidSystemLog() +                                         // certifiedDataType = system-log
                tlv(0x80, "authenticateSmaAdmin".toByteArray()) +        // [0] eventType
                tlv(0x81, "SMA".toByteArray()) +                         // [1] eventOrigin
                tlv(0x82, "smaadmin".toByteArray()) +                    // [2] eventTriggeredByUser
                eventData +                                              // [3] eventData (nested SEQUENCE)
                tlv(0x04, ByteArray(32) { 0x33 }) +                      // serialNumber
                tlv(0x30, oidEcdsaSha384()) +                            // signatureAlgorithm SEQUENCE
                int(8888) +                                              // signatureCounter
                int(1_784_600_000) +                                     // signatureCreationTime (unixTime)
                tlv(0x04, ByteArray(96) { 0x44 })                        // signatureValue
        return tlv(0x30, body)
    }

    private fun enumerated(v: Int): ByteArray = tlv(0x0A, byteArrayOf(v.toByte()))

    /**
     * A version-3 system LogMessage for authenticateUser (TR-03151-1 §2.2), whose eventData [3] is
     * { userId, role, authenticationResult ENUMERATED(incorrectPin=2), remainingRetries }.
     */
    private fun buildV3SystemLogAuthenticateUser(): ByteArray {
        val eventData =
            tlv(
                0xA3,                                                    // [3] IMPLICIT eventData
                tlv(0x13, "TSE-Kasse-1".toByteArray()) +                 // userId PrintableString
                    tlv(0x13, "cashier".toByteArray()) +                 // role PrintableString
                    enumerated(2) +                                      // authenticationResult = incorrectPin(2)
                    int(2),                                              // remainingRetries = 2
            )
        val body =
            int(3) +                                                     // version = 3
                oidSystemLog() +                                         // certifiedDataType = system-log
                tlv(0x80, "authenticateUser".toByteArray()) +            // [0] eventType
                tlv(0x81, "SMA".toByteArray()) +                         // [1] eventOrigin
                tlv(0x82, "TSE-Kasse-1".toByteArray()) +                 // [2] eventTriggeredByUser
                eventData +                                              // [3] eventData
                tlv(0x04, ByteArray(32) { 0x55 }) +                      // serialNumber
                tlv(0x30, oidEcdsaSha384()) +                            // signatureAlgorithm SEQUENCE
                int(9999) +                                              // signatureCounter
                int(1_784_700_000) +                                     // signatureCreationTime (unixTime)
                tlv(0x04, ByteArray(96) { 0x66 })                        // signatureValue
        return tlv(0x30, body)
    }
}
