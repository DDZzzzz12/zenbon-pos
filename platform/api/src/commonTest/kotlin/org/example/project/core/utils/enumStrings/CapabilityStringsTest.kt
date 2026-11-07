package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA admin19 NOTE-c: the permission-group screens showed raw capability codes. */
class CapabilityStringsTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `every capability and every area has a label in every language`() {
        val missing =
            Language.entries.flatMap { language ->
                Capability.entries.flatMap { capability ->
                    val area = capability.name.substringBefore('_')
                    listOfNotNull(
                        capability.name.takeIf { localizedCapabilityName(it, language) == it },
                        area.takeIf { localizedCapabilityArea(it, language) == it && it !in CODE_LIKE_AREAS },
                    ).map { "$language:$it" }
                }
            }
        assertTrue(missing.isEmpty(), "unlabelled: $missing")
    }

    @Test
    fun `a known capability reads as a phrase`() {
        assertEquals("Artikelstamm ansehen", localizedCapabilityName("CATALOG_READ", Language.GERMAN))
        assertEquals("View catalog", localizedCapabilityName("CATALOG_READ", Language.ENGLISH))
        assertEquals("Artikelstamm", localizedCapabilityArea("CATALOG", Language.GERMAN))
    }

    @Test
    fun `an unknown capability from a newer server falls back to its code`() {
        assertEquals("FUTURE_THING", localizedCapabilityName("FUTURE_THING", Language.GERMAN))
        assertEquals("FUTURE", localizedCapabilityArea("FUTURE", Language.CHINESE))
    }

    private companion object {
        /** Areas whose label IS the code — a proper name, not an untranslated one. */
        val CODE_LIKE_AREAS = setOf("ERP", "DATEV", "TSE")
    }
}
