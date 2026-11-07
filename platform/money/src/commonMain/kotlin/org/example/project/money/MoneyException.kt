package org.example.project.money

/** Root of every failure this module raises. No engine-specific exception ever escapes. */
sealed class MoneyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Input did not match the strict grammar `[+-]?digits[.digits]` in ASCII. */
class DecimalParseException(
    input: String,
    reason: String,
) : MoneyException("Cannot parse decimal \"$input\": $reason")

/** A value or a result left the supported precision envelope. */
class DecimalEnvelopeException(
    detail: String,
) : MoneyException("Value outside supported decimal envelope: $detail")

/** Division by zero, or an operation that cannot be performed exactly. */
class DecimalArithmeticException(
    detail: String,
) : MoneyException("Invalid decimal arithmetic: $detail")

/** Currency code was not exactly three A-Z characters. */
class CurrencyFormatException(
    code: String,
) : MoneyException("Invalid ISO-4217 currency code \"$code\": expected 3 uppercase letters")

/** Two different currencies were combined. */
class CurrencyMismatchException(
    left: String,
    right: String,
) : MoneyException("Currency mismatch: cannot combine $left with $right")

/** Allocation arguments were invalid. */
class MoneyAllocationException(
    detail: String,
) : MoneyException("Cannot allocate money: $detail")

/** Summing an empty collection of Money leaves no currency to return. */
class MoneyEmptySumException : MoneyException("Cannot sum an empty collection of Money: no currency to return. Use sumOrZero(currency).")
