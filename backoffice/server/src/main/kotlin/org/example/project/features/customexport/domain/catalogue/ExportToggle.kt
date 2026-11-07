package org.example.project.features.customexport.domain.catalogue

import org.jetbrains.exposed.v1.core.Op

/**
 * A named, pre-written filter an operator may switch on.
 *
 * A closed set of predicates rather than user-composed conditions: the moment a user can compose
 * `AND`/`OR` over chosen operators you have a query language, and a query language is where this kind
 * of feature stops being finishable.
 */
data class ExportToggle(
    val key: String,
    val label: String,
    val predicate: () -> Op<Boolean>,
)
