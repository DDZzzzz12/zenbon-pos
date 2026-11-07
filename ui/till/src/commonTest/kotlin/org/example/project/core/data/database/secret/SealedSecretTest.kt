package org.example.project.core.data.database.secret

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SealedSecretTest {
    @Test
    fun iv_and_tag_without_a_ciphertext_byte_do_not_decode() {
        // 12-byte IV + 16-byte tag: an empty plaintext is never sealed, so this is never a real value.
        assertNull(SealedSecret.decode(SealedSecret.encode(ByteArray(28))))
    }

    @Test
    fun the_smallest_well_formed_value_decodes_to_its_bytes() {
        val bytes = ByteArray(29) { it.toByte() }

        assertContentEquals(bytes, SealedSecret.decode(SealedSecret.encode(bytes)))
    }

    @Test
    fun a_body_that_is_not_base64_does_not_decode() {
        assertNull(SealedSecret.decode("enc:v1:%%%not-base64"))
    }

    @Test
    fun a_value_without_the_prefix_does_not_decode() {
        val body = SealedSecret.encode(ByteArray(29)).removePrefix(SealedSecret.PREFIX)

        assertNull(SealedSecret.decode(body))
    }

    @Test
    fun a_value_of_another_format_version_does_not_decode() {
        val body = SealedSecret.encode(ByteArray(29)).removePrefix(SealedSecret.PREFIX)

        assertNull(SealedSecret.decode("enc:v2:$body"))
    }

    @Test
    fun isSealed_matches_only_this_builds_format() {
        assertTrue(SealedSecret.isSealed(SealedSecret.encode(ByteArray(29))))
        assertFalse(SealedSecret.isSealed("enc:v2:abc"))
        assertFalse(SealedSecret.isSealed("hunter2"))
        assertFalse(SealedSecret.isSealed(""))
    }

    @Test
    fun isFromThisFamily_matches_every_format_version_and_nothing_else() {
        assertTrue(SealedSecret.isFromThisFamily("enc:v1:abc"))
        assertTrue(SealedSecret.isFromThisFamily("enc:v2:abc"))
        assertFalse(SealedSecret.isFromThisFamily("hunter2"))
        assertFalse(SealedSecret.isFromThisFamily(""))
    }
}
