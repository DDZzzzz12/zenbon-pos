/**
 * Configuration model for ZVT terminal connection.
 *
 * Defines all parameters needed to establish and maintain a session
 * with a payment terminal via the ZVT protocol, including network
 * settings, authentication, and protocol behavior flags.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Configuration for connecting to a payment terminal via ZVT protocol.
 *
 * @property host The terminal's IP address or hostname
 * @property port The TCP port (default: 20007 per ZVT standard)
 * @property password Terminal password for authenticated operations (6-digit BCD)
 * @property currency ISO 4217 numeric currency code (default: 978 = EUR)
 * @property configByte Configuration byte flags for Registration (06 00)
 * @property connectionTimeoutMs TCP connection timeout in milliseconds
 * @property readTimeoutMs TCP read timeout for responses in milliseconds
 * @property writeTimeoutMs TCP write timeout in milliseconds
 * @property maxRetries Maximum reconnection attempts before giving up
 * @property retryDelayMs Initial delay between reconnection attempts
 */
data class ZvtConfig(
    val host: String = "127.0.0.1",
    val port: Int = 20007,
    val password: Int = 0,
    val currency: Int = CURRENCY_EUR,
    val configByte: Int = DEFAULT_CONFIG_BYTE,
    val connectionTimeoutMs: Long = 5000L,
    val readTimeoutMs: Long = 120_000L,
    val writeTimeoutMs: Long = 5000L,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 2000L,
) {
    companion object {
        /** ISO 4217 numeric code for Euro */
        const val CURRENCY_EUR: Int = 978

        /** Default ZVT TCP port */
        const val DEFAULT_PORT: Int = 20007

        /**
         * Default config-byte for Registration command.
         *
         * Bit flags (from PA00P015):
         * - Bit 0: ECR controls receipt printing (1 = ECR prints)
         * - Bit 1: ECR controls administration (0 = PT handles)
         * - Bit 2: Reserved
         * - Bit 3: ECR supports intermediate status (1 = yes)
         * - Bit 4-7: Reserved
         *
         * Default: 0x09 = ECR prints receipts + ECR wants intermediate status
         */
        const val DEFAULT_CONFIG_BYTE: Int = 0x09

        /** Config-byte flag: ECR handles receipt printing */
        const val CONFIG_ECR_PRINTS_RECEIPT: Int = 0x01

        /** Config-byte flag: ECR handles administration */
        const val CONFIG_ECR_HANDLES_ADMIN: Int = 0x02

        /** Config-byte flag: ECR supports intermediate status messages */
        const val CONFIG_ECR_INTERMEDIATE_STATUS: Int = 0x08
    }
}
