package com.secureflashcard.wormapi

/**
 * Virtual test double replacing the SWIG-generated enum from `lib/WormAPI.jar`
 * (see [WormError] for how the virtualWorm source set is wired in).
 */
enum class WormInitializationState(private val value: Int) {
    WORM_INIT_UNINITIALIZED(0),
    WORM_INIT_INITIALIZED(1),
    WORM_INIT_DECOMMISSIONED(2),
    ;

    fun swigValue(): Int = value

    companion object {
        @JvmStatic
        fun swigToEnum(swigValue: Int): WormInitializationState =
            entries.firstOrNull { it.value == swigValue }
                ?: throw IllegalArgumentException("No enum class com.secureflashcard.wormapi.WormInitializationState with value $swigValue")
    }
}
