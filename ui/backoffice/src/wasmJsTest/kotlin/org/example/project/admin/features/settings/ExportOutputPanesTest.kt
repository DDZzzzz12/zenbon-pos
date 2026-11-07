package org.example.project.admin.features.settings

import org.example.project.admin.features.settings.presentation.sections.ExportOutputPane
import org.example.project.admin.features.settings.presentation.sections.exportOutputPanes
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pane exists exactly when its capability is held; a denied panel must not leave an empty chip. */
class ExportOutputPanesTest {
    @Test
    fun accountingReadAloneShowsOnlyTheDatevOutput() {
        assertEquals(listOf(ExportOutputPane.DATEV_OUTPUT), exportOutputPanes(SetAccessPolicy(setOf(Capability.ACCOUNTING_READ))))
    }

    @Test
    fun dsfinvkExportAloneShowsOnlyTheDsfinvkPane() {
        assertEquals(listOf(ExportOutputPane.DSFINVK), exportOutputPanes(SetAccessPolicy(setOf(Capability.DSFINVK_EXPORT))))
    }

    @Test
    fun bothCapabilitiesShowBothInPageOrder() {
        val both = SetAccessPolicy(setOf(Capability.DSFINVK_EXPORT, Capability.ACCOUNTING_READ))
        assertEquals(listOf(ExportOutputPane.DATEV_OUTPUT, ExportOutputPane.DSFINVK), exportOutputPanes(both))
    }

    @Test
    fun neitherCapabilityShowsNothing() {
        assertEquals(emptyList(), exportOutputPanes(SetAccessPolicy(setOf(Capability.CONFIG_WRITE))))
    }
}
