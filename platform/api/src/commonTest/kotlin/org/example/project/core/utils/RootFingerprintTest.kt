package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootFingerprintTest {
    private val sequential = ByteArray(16) { it.toByte() }

    @Test
    fun encodes_the_first_128_bits_as_grouped_crockford_base32() {
        assertEquals("000G-40R4-0M30-E209-185G-R38E-1W", RootFingerprint.fromDigest(sequential))
        assertEquals("0000-0000-0000-0000-0000-0000-00", RootFingerprint.fromDigest(ByteArray(16)))
        assertEquals("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW", RootFingerprint.fromDigest(ByteArray(16) { -1 }))
    }

    @Test
    fun bytes_after_the_first_sixteen_do_not_matter() {
        assertEquals(RootFingerprint.fromDigest(sequential), RootFingerprint.fromDigest(sequential + ByteArray(16) { 9 }))
    }

    @Test
    fun a_digest_shorter_than_sixteen_bytes_is_refused() {
        assertFailsWith<IllegalArgumentException> { RootFingerprint.fromDigest(ByteArray(15)) }
    }

    @Test
    fun normalize_ignores_case_spaces_and_dashes() {
        val canonical = "000G40R40M30E209185GR38E1W"
        assertEquals(canonical, RootFingerprint.normalize("000G-40R4-0M30-E209-185G-R38E-1W"))
        assertEquals(canonical, RootFingerprint.normalize(" 000g 40r4 0m30 e209 185g r38e 1w "))
    }

    @Test
    fun normalize_maps_the_crockford_look_alikes() {
        assertEquals("000G40R40M30E209185GR38E1W", RootFingerprint.normalize("OO0G-4OR4-OM3O-E2O9-I85G-R38E-lW"))
    }

    @Test
    fun normalize_rejects_wrong_length_foreign_characters_and_set_spare_bits() {
        assertNull(RootFingerprint.normalize(""))
        assertNull(RootFingerprint.normalize("000G-40R4-0M30-E209-185G-R38E"))
        assertNull(RootFingerprint.normalize("000G-40R4-0M30-E209-185G-R38E-1WW"))
        assertNull(RootFingerprint.normalize("000G-40R4-0M30-E209-185G-R38E-1U")) // U is not in the alphabet
        assertNull(RootFingerprint.normalize("000G-40R4-0M30-E209-185G-R38E-1X")) // spare bits set
    }

    @Test
    fun matches_accepts_the_code_of_the_digest_only() {
        assertTrue(RootFingerprint.matches("000g 40r4 0m30 e209 185g r38e 1w", sequential))
        assertFalse(RootFingerprint.matches("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW", sequential))
        assertFalse(RootFingerprint.matches("not a code", sequential))
    }
}
