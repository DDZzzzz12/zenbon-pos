package org.example.project.ui.components

/**
 * How a [PosTextField] draws itself. Replaces the old `outlined: Boolean`, which could not express a
 * third rendering.
 */
enum class PosTextFieldAppearance {
    /** Material's outlined box with a floating label notched into the border. The form default. */
    Outlined,

    /** No container and no indicator line, for table cells that read as plain text until edited. */
    Borderless,

    /**
     * The flat surface [PosSearchBar] uses — rounded tonal container, no outline, no notch — with
     * the label as plain text ABOVE the box instead of cut into its border.
     */
    Flat,
}
