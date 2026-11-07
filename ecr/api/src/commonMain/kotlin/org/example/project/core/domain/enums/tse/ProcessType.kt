package org.example.project.core.domain.enums.tse

enum class ProcessType(
    val value: String,
) {
    KASSENBELEG_V1("Kassenbeleg-V1"),
    BESTELLUNG_V1("Bestellung-V1"),
    SONSTIGER_VORGANG("SonstigerVorgang"),
    ;

    override fun toString(): String = value
}
