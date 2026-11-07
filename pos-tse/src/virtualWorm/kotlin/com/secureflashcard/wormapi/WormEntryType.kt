package com.secureflashcard.wormapi

/**
 * Virtual test double replacing the SWIG-generated enum from `lib/WormAPI.jar`
 * (see [WormError] for how the virtualWorm source set is wired in).
 */
enum class WormEntryType(private val value: Int) {
    WORM_ENTRY_TYPE_TRANSACTION(0),
    WORM_ENTRY_TYPE_SYSTEM_LOG_MESSAGE(1),
    WORM_ENTRY_TYPE_SE_AUDIT_LOG_MESSAGE(2),
    ;

    fun swigValue(): Int = value

    companion object {
        @JvmStatic
        fun swigToEnum(swigValue: Int): WormEntryType =
            entries.firstOrNull { it.value == swigValue }
                ?: throw IllegalArgumentException("No enum class com.secureflashcard.wormapi.WormEntryType with value $swigValue")
    }
}
