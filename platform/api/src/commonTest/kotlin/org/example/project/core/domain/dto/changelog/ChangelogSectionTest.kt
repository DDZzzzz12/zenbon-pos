package org.example.project.core.domain.dto.changelog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A release-note bullet that begins with `## ` is a section heading, not a bullet: the changelog
 * screen renders it as a label over the bullets that follow, which is how a release marker groups
 * its fiscal items (TSE signing, DSFinV-K export) apart from the rest.
 */
class ChangelogSectionTest {
    @Test
    fun `a bullet starting with the marker is a heading`() {
        assertTrue(ChangelogSection.isHeading("## TSE signing and DSFinV-K export"))
    }

    @Test
    fun `an ordinary bullet is not a heading`() {
        assertFalse(ChangelogSection.isHeading("TSE signing: more than sixty corrections."))
        assertFalse(ChangelogSection.isHeading("##no space is not a heading"))
    }

    @Test
    fun `heading text drops the marker and surrounding whitespace`() {
        assertEquals("TSE-Signatur und DSFinV-K-Export", ChangelogSection.headingText("##  TSE-Signatur und DSFinV-K-Export "))
    }
}
