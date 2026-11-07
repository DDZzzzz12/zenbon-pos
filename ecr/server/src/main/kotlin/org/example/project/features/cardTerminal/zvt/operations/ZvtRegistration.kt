/**
 * ZVT Registration (06 00) operation builder.
 *
 * Registration initializes the ECR↔PT session, exchanges configuration
 * parameters, and establishes the communication protocol. It must be
 * the first command sent after establishing the TCP connection.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.operations

import org.example.project.features.cardTerminal.zvt.models.ZvtConfig
import org.example.project.features.cardTerminal.zvt.protocol.*

/**
 * Builds the Registration (06 00) command APDU.
 *
 * The Registration command includes:
 * - Password (BMP 3D): Terminal password for authentication
 * - Config byte: Flags controlling receipt printing, intermediate status, etc.
 * - Currency code (BMP 49): ISO 4217 code for the operating currency
 * - Optional TLV container (BMP 06): Declaring supported TLV tags
 */
object ZvtRegistration {
    /**
     * Builds a Registration APDU from the given configuration.
     *
     * @param config The terminal configuration
     * @return The Registration command APDU
     */
    fun buildApdu(config: ZvtConfig): ZvtApdu {
        val fields = mutableMapOf<Int, ByteArray>()

        // Password (BMP 3D)
        fields[BmpFieldDefinition.PASSWORD.fieldId] = ZvtBcdCodec.encodePassword(config.password)

        // Config byte is part of the data block but not a standard BMP field.
        // It's typically sent as the "service byte" (BMP 03) in Registration.
        fields[BmpFieldDefinition.SERVICE_BYTE.fieldId] = byteArrayOf(config.configByte.toByte())

        // Currency code (BMP 49)
        fields[BmpFieldDefinition.CURRENCY_CODE.fieldId] = ZvtBcdCodec.encodeCurrency(config.currency)

        return ZvtApdu.create(ZvtCommand.REGISTRATION, fields)
    }

    /**
     * Builds a config byte from individual flags.
     *
     * @param ecrPrintsReceipt ECR handles receipt printing (bit 0)
     * @param ecrHandlesAdmin ECR handles administration functions (bit 1)
     * @param ecrWantsIntermediateStatus ECR requests intermediate status messages (bit 3)
     * @return The constructed config byte
     */
    fun buildConfigByte(
        ecrPrintsReceipt: Boolean = true,
        ecrHandlesAdmin: Boolean = false,
        ecrWantsIntermediateStatus: Boolean = true,
    ): Int {
        var configByte = 0
        if (ecrPrintsReceipt) configByte = configByte or ZvtConfig.CONFIG_ECR_PRINTS_RECEIPT
        if (ecrHandlesAdmin) configByte = configByte or ZvtConfig.CONFIG_ECR_HANDLES_ADMIN
        if (ecrWantsIntermediateStatus) configByte = configByte or ZvtConfig.CONFIG_ECR_INTERMEDIATE_STATUS
        return configByte
    }
}
