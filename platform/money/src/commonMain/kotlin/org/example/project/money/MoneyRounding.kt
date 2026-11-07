package org.example.project.money

/**
 * Rounding policies supported on every engine.
 *
 * [DOWN] and [UP] are toward / away from zero, matching java.math.RoundingMode — NOT Apple's
 * NSRoundDown/NSRoundUp, which mean FLOOR/CEILING. The iOS engine emulates these two.
 */
enum class MoneyRounding {
    HALF_UP,
    HALF_EVEN,
    DOWN,
    UP,
    FLOOR,
    CEILING,
}
