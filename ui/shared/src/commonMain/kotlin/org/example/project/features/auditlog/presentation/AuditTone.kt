package org.example.project.features.auditlog.presentation

/** How an entry-log operation is coloured. [Neutral] carries no colour meaning (e.g. a deactivation). */
internal enum class AuditTone { Create, Update, Delete, Error, Neutral }
