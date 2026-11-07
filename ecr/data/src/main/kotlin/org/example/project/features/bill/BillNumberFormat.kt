package org.example.project.features.bill

/**
 * The Kassenbeleg number prefix — `RE-yyMMdd-{n}`, produced by `BillRepositoryImpl`.
 *
 * Extracted from that literal so the Ausgangsrechnung range has something real to refuse. A guard
 * comparing one hard-coded string to a *copy* of another hard-coded string stops guarding anything
 * the moment either moves; this makes the collision check follow the format.
 *
 * `RE` is unfortunate as a receipt prefix — it reads as *Rechnung* while a Kassenbeleg is not one —
 * but it is in production data and renaming it would rewrite history for no gain.
 */
const val BILL_NUMBER_PREFIX = "RE"
