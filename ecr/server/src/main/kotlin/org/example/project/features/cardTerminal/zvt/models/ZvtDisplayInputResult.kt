/**
 * Display Text with Input (06 E2) result model.
 *
 * Contains the numeric input entered by the customer on the terminal
 * in response to a Display Text + Input command.
 *
 * @since 1.0.0
 */
package org.example.project.features.cardTerminal.zvt.models

/**
 * Result of a Display Text with Input operation.
 *
 * @property success Whether the input was received successfully
 * @property inputData The numeric string entered by the customer
 * @property resultCode The raw result code from the terminal
 * @property errorDescription Error description if input was cancelled or timed out
 */
data class ZvtDisplayInputResult(
    val success: Boolean,
    val inputData: String = "",
    val resultCode: Int = 0,
    val errorDescription: String = "",
)
