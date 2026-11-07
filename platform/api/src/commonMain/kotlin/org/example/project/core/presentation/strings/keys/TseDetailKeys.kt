package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TseDetailKeys {
    val title = StringKey("tse.detail.title")
    val transactionNumber = StringKey("tse.detail.transactionNumber")
    val signatureCounter = StringKey("tse.detail.signatureCounter")
    val signatureAlgorithm = StringKey("tse.detail.signatureAlgorithm")
    val processStart = StringKey("tse.detail.processStart")
    val processEnd = StringKey("tse.detail.processEnd")
    val processStartUtc = StringKey("tse.detail.processStartUtc")
    val processEndUtc = StringKey("tse.detail.processEndUtc")
    val clientId = StringKey("tse.detail.clientId")
    val logTimeFormat = StringKey("tse.detail.logTimeFormat")
    val signature = StringKey("tse.detail.signature")
    val publicKey = StringKey("tse.detail.publicKey")

    // Nachsignierung record (tse_late_signing): a Vorgang the TSE could not sign immediately.
    val lateSigningTitle = StringKey("tse.detail.lateSigning.title")
    val lateSigningPending = StringKey("tse.detail.lateSigning.pending")
    val lateSigningDone = StringKey("tse.detail.lateSigning.done")
    val lateSigningAbandoned = StringKey("tse.detail.lateSigning.abandoned")
    val lateSigningReason = StringKey("tse.detail.lateSigning.reason")
    val lateSigningQueuedAt = StringKey("tse.detail.lateSigning.queuedAt")
}
