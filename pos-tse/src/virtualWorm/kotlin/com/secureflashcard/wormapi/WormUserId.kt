package com.secureflashcard.wormapi

/**
 * Virtual test double replacing the SWIG-generated enum from `lib/WormAPI.jar`
 * (see [WormError] for how the virtualWorm source set is wired in).
 */
enum class WormUserId(private val value: Int) {
    WORM_USER_UNAUTHENTICATED(0),
    WORM_USER_ADMIN(1),
    WORM_USER_TIME_ADMIN(2),

    /**
     * TSE 2.0 only. Transactions require this user, whom the SDK logs in itself using
     * a PIN it sets during setup — the ERS never learns it.
     */
    WORM_USER_LOGGER(3),
    ;

    fun swigValue(): Int = value

    companion object {
        @JvmStatic
        fun swigToEnum(swigValue: Int): WormUserId =
            entries.firstOrNull { it.value == swigValue }
                ?: throw IllegalArgumentException("No enum class com.secureflashcard.wormapi.WormUserId with value $swigValue")
    }
}
