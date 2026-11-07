package org.example.project.money

import kotlinx.serialization.Serializable
import org.example.project.money.serialization.CurrencySerializer

/** An ISO-4217 currency code: exactly three uppercase ASCII letters. */
@Serializable(with = CurrencySerializer::class)
data class Currency(
    val code: String,
) {
    init {
        if (code.length != 3 || code.any { it < 'A' || it > 'Z' }) {
            throw CurrencyFormatException(code)
        }
    }

    override fun toString(): String = code

    companion object {
        val EUR: Currency = Currency("EUR")
        val USD: Currency = Currency("USD")
        val CHF: Currency = Currency("CHF")

        fun parse(code: String): Currency = Currency(code)
    }
}
