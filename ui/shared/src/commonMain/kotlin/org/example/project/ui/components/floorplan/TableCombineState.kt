package org.example.project.ui.components.floorplan

/**
 * Visual state of a table while the till is in combine mode.
 *
 * This is a rendering input, not behaviour — the gesture that produces it lives in the host app.
 * A host with no combine mode (the admin editor) passes [NONE].
 */
enum class TableCombineState {
    NONE,
    CONNECTABLE,
    INCOMPATIBLE,
    SOURCE,
    VALID_TARGET,
    DISCONNECT_TARGET,
    CONNECTED,
}
