package org.example.project.core.domain.enums.tse

enum class BonType(
    val value: String,
) {
    BELEG("Beleg"),
    AV_TRANSFER("AVTransfer"),
    AV_BESTELLUNG("AVBestellung"),
    AV_TRAINING("AVTraining"),
    AV_BELEGSTORNO("AVBelegstorno"),
    AV_BELEGABBRUCH("AVBelegabbruch"),
    AV_SACHBEZUG("AVSachbezug"),
    AV_SONSTIGE("AVSonstige"),
    AV_RECHNUNG("AVRechnung"),
    ;

    override fun toString(): String = value
}
