package org.example.project.core.domain.enums.setup

/**
 * Where `/` forwards a browser. [wire] is the value stored in `start_page_default`, sent over the API and
 * kept in the `pos_start` cookie; [path] is the only place a redirect may go. NONE means "show the start page".
 */
enum class StartPageTarget(
    val wire: String,
    val path: String?,
) {
    NONE("none", null),
    ADMIN("admin", "/admin/"),
    POS("pos", "/pos/"),
    ;

    companion object {
        /** Unknown or blank values are not a target — callers treat null as "ignore". */
        fun fromWire(value: String?): StartPageTarget? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}
