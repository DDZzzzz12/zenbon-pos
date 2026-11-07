package org.example.project.admin.core.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteConfirmTitleTest {
    @Test fun germanTemplateNamesTheObjectInTypographicQuotes() {
        assertEquals("„Cola 0,5l“ löschen?", deleteConfirmTitle("„{0}“ löschen?", "Cola 0,5l"))
    }

    @Test fun englishAndChineseTemplatesKeepTheirOwnQuotes() {
        assertEquals("Delete “Cola”?", deleteConfirmTitle("Delete “{0}”?", "Cola"))
        assertEquals("删除“可乐”？", deleteConfirmTitle("删除“{0}”？", "可乐"))
    }

    @Test fun nameIsTrimmed() {
        assertEquals("„Tisch 7“ löschen?", deleteConfirmTitle("„{0}“ löschen?", "  Tisch 7 "))
    }

    @Test fun blankNameFallsBackSoNoEmptyQuotesAppear() {
        assertEquals("„Export“ löschen?", deleteConfirmTitle("„{0}“ löschen?", "   ", fallback = "Export"))
    }

    @Test fun blockedTitleTakesTheEntityType() {
        assertEquals("Kategorie kann nicht gelöscht werden", deleteConfirmTitle("{0} kann nicht gelöscht werden", "Kategorie"))
    }
}
