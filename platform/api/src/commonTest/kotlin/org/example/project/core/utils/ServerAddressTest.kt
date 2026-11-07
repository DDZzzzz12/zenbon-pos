package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressTest {
    @Test
    fun a_full_address_gains_the_trailing_slash() {
        assertEquals("http://192.168.1.20:8080/", ServerAddress.normalize("http://192.168.1.20:8080"))
    }

    @Test
    fun a_missing_scheme_means_https() {
        assertEquals("https://192.168.1.20:8080/", ServerAddress.normalize("192.168.1.20:8080"))
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        assertEquals("http://kasse.local:8080/", ServerAddress.normalize("  http://kasse.local:8080/ "))
    }

    @Test
    fun https_and_a_path_prefix_survive() {
        assertEquals("https://pos.example.com/kasse/", ServerAddress.normalize("https://pos.example.com/kasse//"))
    }

    @Test
    fun the_scheme_is_matched_case_insensitively() {
        assertEquals("http://host:8080/", ServerAddress.normalize("HTTP://host:8080"))
    }

    @Test
    fun an_ipv6_literal_keeps_its_port() {
        assertEquals("http://[fe80::1]:8080/", ServerAddress.normalize("http://[fe80::1]:8080"))
    }

    @Test
    fun blank_is_rejected() {
        assertNull(ServerAddress.normalize("   "))
    }

    @Test
    fun another_scheme_is_rejected() {
        assertNull(ServerAddress.normalize("ftp://192.168.1.20"))
    }

    @Test
    fun an_inner_space_is_rejected() {
        assertNull(ServerAddress.normalize("http://192.168.1 .20:8080"))
    }

    @Test
    fun a_missing_host_is_rejected() {
        assertNull(ServerAddress.normalize("http://"))
        assertNull(ServerAddress.normalize("http://:8080"))
        assertNull(ServerAddress.normalize("http://[]:8080"))
    }

    @Test
    fun a_bad_port_is_rejected() {
        assertNull(ServerAddress.normalize("http://host:"))
        assertNull(ServerAddress.normalize("http://host:abc"))
        assertNull(ServerAddress.normalize("http://host:0"))
        assertNull(ServerAddress.normalize("http://host:70000"))
    }

    @Test
    fun a_query_or_fragment_is_rejected() {
        assertNull(ServerAddress.normalize("http://host:8080/?x=1"))
        assertNull(ServerAddress.normalize("http://host:8080#top"))
    }

    @Test
    fun credentials_are_rejected() {
        assertNull(ServerAddress.normalize("http://user:pw@host:8080"))
    }

    @Test
    fun localhost_is_loopback_with_or_without_scheme_and_port() {
        assertTrue(ServerAddress.isLoopback("http://localhost:8080"))
        assertTrue(ServerAddress.isLoopback("localhost:8080/"))
        assertTrue(ServerAddress.isLoopback("HTTP://LocalHost"))
    }

    @Test
    fun the_ipv4_and_ipv6_loopback_literals_are_loopback() {
        assertTrue(ServerAddress.isLoopback("http://127.0.0.1:8080"))
        assertTrue(ServerAddress.isLoopback("http://[::1]:8080/"))
    }

    @Test
    fun the_wildcard_bind_address_and_any_127_dot_ipv4_host_are_loopback() {
        assertTrue(ServerAddress.isLoopback("http://0.0.0.0:8080"))
        assertTrue(ServerAddress.isLoopback("127.0.1.1:8080"))
    }

    @Test
    fun a_lan_address_or_a_host_merely_starting_with_localhost_is_not_loopback() {
        assertFalse(ServerAddress.isLoopback("http://192.168.1.20:8080"))
        assertFalse(ServerAddress.isLoopback("http://localhost.example.com:8080"))
        assertFalse(ServerAddress.isLoopback("http://[fe80::1]:8080"))
        assertFalse(ServerAddress.isLoopback("http://127host.example:8080"))
        assertFalse(ServerAddress.isLoopback("http://10.0.0.127:8080"))
    }

    @Test
    fun an_invalid_address_is_not_loopback() {
        assertFalse(ServerAddress.isLoopback("   "))
        assertFalse(ServerAddress.isLoopback("ftp://localhost"))
    }
}
