package org.example.project.features.auditlog

import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.presentation.strings.keys.AuditLogKeys
import org.example.project.core.presentation.strings.translations.ChineseFeaturesStrings
import org.example.project.core.presentation.strings.translations.EnglishFeaturesStrings
import org.example.project.core.presentation.strings.translations.GermanFeaturesStrings
import org.example.project.features.auditlog.presentation.AuditTone
import org.example.project.features.auditlog.presentation.entityTypeKey
import org.example.project.features.auditlog.presentation.entryOperationKey
import org.example.project.features.auditlog.presentation.entryOperationTone
import org.example.project.features.auditlog.presentation.humanizeAuditValue
import org.example.project.features.auditlog.presentation.transactionOperationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuditLabelsTest {
    private val languages =
        listOf(EnglishFeaturesStrings.all, GermanFeaturesStrings.all, ChineseFeaturesStrings.all)

    @Test
    fun everyTransactionOperationHasALabel() {
        for (op in TransactionAuditDto.TransactionOperation.entries) {
            assertNotNull(transactionOperationKey(op.name), "no label for ${op.name}")
        }
    }

    @Test
    fun everyTransactionEntityTypeHasALabel() {
        for (type in TransactionAuditDto.EntityType.entries) {
            assertNotNull(entityTypeKey(type.name), "no label for ${type.name}")
        }
    }

    @Test
    fun everyEntryLogEntityTypeAndOperationHasALabel() {
        for (type in ChangeEntityType.entries) {
            assertNotNull(entityTypeKey(type.name), "no label for ${type.name}")
        }
        for (op in LogOperation.entries) {
            assertNotNull(entryOperationKey(op.name, null), "no label for ${op.name}")
        }
    }

    @Test
    fun serverSpellingsOutsideTheEnumsResolve() {
        assertEquals(entityTypeKey("ORDER_ITEM"), entityTypeKey("OrderItem"))
        assertEquals(entityTypeKey("ZBON"), entityTypeKey("Z-report"))
        assertNotNull(entityTypeKey("payment_allocation"))
        assertNotNull(entityTypeKey("TSE_EXPLANATION"))
        assertNotNull(transactionOperationKey("CANCEL_ORDER_ITEMS"))
    }

    @Test
    fun deactivatedPrefixTurnsDeleteIntoDeactivatedAndNeutral() {
        assertEquals(AuditLogKeys.entryOpDeactivated, entryOperationKey("DELETE", "Deactivated VOUCHER product"))
        assertEquals(AuditTone.Neutral, entryOperationTone("DELETE", "Deactivated GUTSCHEIN payment type"))
        assertEquals(AuditLogKeys.entryOpDelete, entryOperationKey("DELETE", "Removed product"))
        assertEquals(AuditLogKeys.entryOpDelete, entryOperationKey("DELETE", null))
        assertEquals(AuditTone.Delete, entryOperationTone("DELETE", null))
        assertEquals(AuditTone.Create, entryOperationTone("CREATE", "Deactivated"))
    }

    /** FO-59: the German "Deaktiviert" message is the same deactivation — same label AND same tone. */
    @Test
    fun germanDeaktiviertReadsAndColoursLikeDeactivated() {
        for (message in listOf("Deactivated terminal 3", "  Deaktiviert: Drucker 2", "deaktiviert Drucker 2")) {
            assertEquals(AuditLogKeys.entryOpDeactivated, entryOperationKey("DELETE", message), message)
            assertEquals(AuditTone.Neutral, entryOperationTone("DELETE", message), message)
        }
        assertEquals(AuditLogKeys.entryOpDelete, entryOperationKey("DELETE", ""))
        assertEquals(AuditTone.Delete, entryOperationTone("DELETE", "Deleted product 'Cola'"))
        assertEquals(AuditTone.Update, entryOperationTone("UPDATE", "Deaktiviert"))
        assertEquals(AuditTone.Error, entryOperationTone("ERROR", "Deactivated"))
    }

    @Test
    fun unknownValuesFallBackToHumanizedRawNeverThrow() {
        assertNull(transactionOperationKey("SOME_FUTURE_OPERATION"))
        assertNull(entityTypeKey("SomeFutureType"))
        assertNull(entryOperationKey("MYSTERY", null))
        assertEquals("Some future operation", humanizeAuditValue("SOME_FUTURE_OPERATION"))
        assertEquals("Some future type", humanizeAuditValue("SomeFutureType"))
        assertEquals("", humanizeAuditValue(""))
    }

    @Test
    fun everyAuditLabelKeyHasAllThreeTranslations() {
        val values =
            TransactionAuditDto.TransactionOperation.entries.mapNotNull { transactionOperationKey(it.name) } +
                TransactionAuditDto.EntityType.entries.mapNotNull { entityTypeKey(it.name) } +
                ChangeEntityType.entries.mapNotNull { entityTypeKey(it.name) } +
                LogOperation.entries.mapNotNull { entryOperationKey(it.name, null) } +
                AuditLogKeys.entryOpDeactivated
        for (language in languages) {
            for (key in values) assertNotNull(language[key], "missing translation for ${key.id}")
        }
    }
}
