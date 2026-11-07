package org.example.project.core.utils

/**
 * The factory placeholder credentials a TSE must never be commissioned with.
 *
 * Setup writes the Admin PIN, the Admin PUK and the TimeAdmin PIN to the device one-way, so a value
 * nobody chose is permanent. `pos-tse`'s `TseInitializationManager` refuses these same values, but
 * only in its log — the operator saw `500 TSE setup failed` (E2E round 4, R4-13). This copy lets the
 * server name the offending field in its refusal and the setup dialogs refuse it before sending.
 * Keep it in step with `TseInitializationManager.FACTORY_PLACEHOLDER_PINS`/`_PUKS`.
 */
object TseCredentialPlaceholders {
    private val PINS = setOf("12345", "23456")
    private val PUKS = setOf("123456")

    fun isPlaceholderPin(pin: String): Boolean = pin in PINS

    fun isPlaceholderPuk(puk: String): Boolean = puk in PUKS

    /** The names of the credentials still set to a placeholder, in the order setup reads them. */
    fun placeholderFields(
        adminPin: String,
        adminPuk: String,
        timeAdminPin: String,
    ): List<String> =
        buildList {
            if (isPlaceholderPin(adminPin)) add("Admin PIN")
            if (isPlaceholderPuk(adminPuk)) add("Admin PUK")
            if (isPlaceholderPin(timeAdminPin)) add("TimeAdmin PIN")
        }
}
