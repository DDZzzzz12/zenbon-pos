package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/** Localization keys for the TSE archive register ("Archiv registrieren", real-TSE QA rt14). */
object TseArchiveKeys {
    val registerTitle = StringKey("tse_archive.register_title")
    val registerDescription = StringKey("tse_archive.register_description")
    val fileNameLabel = StringKey("tse_archive.file_name_label")
    val registerButton = StringKey("tse_archive.register_button")
    val registering = StringKey("tse_archive.registering")
    val registered = StringKey("tse_archive.registered") // "{0}: TSE {1}, TANR {2}–{3}"
    val notRegisteredAfterDelete = StringKey("tse_archive.not_registered_after_delete") // "{0}"
}
