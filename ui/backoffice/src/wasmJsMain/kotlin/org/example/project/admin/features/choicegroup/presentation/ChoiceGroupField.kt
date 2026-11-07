package org.example.project.admin.features.choicegroup.presentation

/** The choice-group editor fields that carry a client-side validation error, in on-screen order. */
enum class ChoiceGroupField {
    NAME,

    /** Min and max selections together: the rule is a relation between the two. */
    SELECTION_RANGE,

    /** The price deltas of the option rows; each unreadable row is marked at its own field. */
    OPTION_PRICES,
}
