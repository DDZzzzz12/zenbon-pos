package com.secureflashcard.wormapi

/**
 * Virtual test double replacing the SWIG-generated enum from `lib/WormAPI.jar`
 * (see [WormError] for how the virtualWorm source set is wired in).
 */
enum class WormTseFirmwareUpdate(private val value: Int) {
    WORM_FW_1_1_0_USB(0),
    WORM_FW_NONE(1),
    ;

    fun swigValue(): Int = value

    companion object {
        @JvmStatic
        fun swigToEnum(swigValue: Int): WormTseFirmwareUpdate =
            entries.firstOrNull { it.value == swigValue }
                ?: throw IllegalArgumentException("No enum class com.secureflashcard.wormapi.WormTseFirmwareUpdate with value $swigValue")
    }
}
