package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlPolicyTest {
    @Test
    fun https_to_any_host_is_accepted() {
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("https://kasse.example.de/"))
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("https://192.168.1.10"))
    }

    @Test
    fun plain_http_to_a_lan_host_is_insecure() {
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("http://192.168.1.10:8080/"))
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("http://kasse.local"))
    }

    @Test
    fun plain_http_to_loopback_stays_allowed_for_development() {
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("http://localhost:8080/"))
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("http://127.0.0.1:8080"))
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("http://[::1]:8080/"))
    }

    @Test
    fun the_android_emulator_alias_is_not_loopback() {
        // 10.0.2.2 is an ordinary LAN address in a real shop. Emulator dev uses `adb reverse`.
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("http://10.0.2.2:8080"))
    }

    @Test
    fun anything_without_a_scheme_and_host_is_malformed() {
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate(""))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("kasse.example.de"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("ftp://kasse.example.de"))
    }

    @Test
    fun loopback_detection_does_not_trust_lookalike_names() {
        assertTrue(LoopbackHosts.isLoopback("127.0.0.53"))
        assertTrue(LoopbackHosts.isLoopback("LOCALHOST"))
        assertFalse(LoopbackHosts.isLoopback("127.example.de"))
        assertFalse(LoopbackHosts.isLoopback("localhost.example.de"))
        assertFalse(LoopbackHosts.isLoopback("0.0.0.0"))
    }

    @Test
    fun a_backslash_is_malformed() {
        // Rejected by the up-front character check: Ktor ends the authority at `\`, while browsers
        // and OkHttp read it as `/` and would connect to 192.168.1.10 over plain HTTP.
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("""http://\192.168.1.10:8080/"""))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("""http://\"""))
    }

    @Test
    fun an_empty_host_is_malformed_instead_of_falling_back_to_the_origin_host() {
        // Ktor's Url(...) fills an empty host from the origin (localhost / window.location).
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://#x"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://#x"))
    }

    @Test
    fun the_host_is_the_one_a_browser_would_connect_to() {
        // `@` inside the fragment is not userinfo: the host is localhost.
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("http://localhost#@192.168.1.10"))
        // `localhost:8080` before `@` is userinfo, not host and port: the host is 192.168.1.10.
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://localhost:8080@192.168.1.10"))
        // A third slash does not hide the host: browsers connect to 192.168.1.10.
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("http:///192.168.1.10"))
    }

    @Test
    fun whitespace_or_control_characters_inside_the_url_are_malformed() {
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://192.168.1.10 /"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://local host"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://kasse\t.example.de"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://kasse.example.de/\nx"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://kasse\u0000.example.de"))
    }

    @Test
    fun surrounding_whitespace_is_ignored() {
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("  https://kasse.example.de  "))
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("\thttp://192.168.1.10\n"))
    }

    @Test
    fun the_scheme_is_case_insensitive() {
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("HTTPS://kasse.example.de"))
        assertEquals(ServerUrlVerdict.INSECURE_REMOTE, ServerUrlPolicy.evaluate("HTTP://192.168.1.10"))
    }

    @Test
    fun credentials_in_the_url_are_malformed() {
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("https://admin:secret@kasse.example.de"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://u:p@localhost:8080"))
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://localhost@192.168.1.10"))
    }

    @Test
    fun the_expanded_ipv6_loopback_is_accepted_over_http() {
        assertEquals(ServerUrlVerdict.ACCEPTED, ServerUrlPolicy.evaluate("http://[0:0:0:0:0:0:0:1]:8080/"))
    }

    @Test
    fun an_out_of_range_port_is_malformed() {
        assertEquals(ServerUrlVerdict.MALFORMED, ServerUrlPolicy.evaluate("http://192.168.1.10:99999"))
    }

    @Test
    fun ipv4_loopback_must_be_a_valid_address() {
        assertTrue(LoopbackHosts.isLoopback("127.255.255.255"))
        assertTrue(LoopbackHosts.isLoopback("127.0.0.0"))
        assertFalse(LoopbackHosts.isLoopback("127.0.0.999"))
        assertFalse(LoopbackHosts.isLoopback("127.256.0.1"))
        // Leading zeros are read as octal by some stacks and rejected by others (then resolved via DNS).
        assertFalse(LoopbackHosts.isLoopback("127.0.0.01"))
    }

    @Test
    fun suggest_https_switches_the_scheme_and_drops_the_legacy_port() {
        assertEquals("https://192.168.1.10/", ServerUrlPolicy.suggestHttps("http://192.168.1.10:8080/"))
        assertEquals("https://kasse/pos/", ServerUrlPolicy.suggestHttps("HTTP://kasse:8080/pos/"))
        assertEquals("https://kasse/", ServerUrlPolicy.suggestHttps("http://kasse:80/"))
        assertEquals("https://kasse/", ServerUrlPolicy.suggestHttps("http://kasse/"))
    }

    @Test
    fun suggest_https_keeps_ipv6_brackets_and_other_ports() {
        assertEquals("https://[fe80::1]/", ServerUrlPolicy.suggestHttps("http://[fe80::1]:8080/"))
        assertEquals("https://host:9090/", ServerUrlPolicy.suggestHttps("http://host:9090/"))
        assertEquals("https://host:18080/api", ServerUrlPolicy.suggestHttps("http://host:18080/api?x=1"))
    }

    @Test
    fun suggest_https_drops_the_query_and_fragment_but_keeps_the_path() {
        // The suggestion is used as a base URL that every request path is appended to; a stray
        // `?a=b#f` surviving onto `/api/...` would corrupt it.
        assertEquals("https://host/", ServerUrlPolicy.suggestHttps("http://host:8080/?a=b#f"))
        assertEquals("https://192.168.1.10/pos/", ServerUrlPolicy.suggestHttps("http://192.168.1.10:8080/pos/"))
    }

    @Test
    fun suggest_https_leaves_accepted_and_malformed_addresses_alone() {
        // The textual replace turned this into the accepted https://host0/. Port 80800 does not parse.
        assertEquals("http://host:80800/", ServerUrlPolicy.suggestHttps("http://host:80800/"))
        assertEquals("https://kasse/", ServerUrlPolicy.suggestHttps("https://kasse/"))
        assertEquals("https://kasse:8080/", ServerUrlPolicy.suggestHttps("https://kasse:8080/"))
        assertEquals("http://localhost:8080/", ServerUrlPolicy.suggestHttps("http://localhost:8080/"))
        assertEquals("kasse.example.de", ServerUrlPolicy.suggestHttps("kasse.example.de"))
    }

    @Test
    fun brackets_are_only_stripped_around_ipv6_addresses() {
        assertTrue(LoopbackHosts.isLoopback("[::1]"))
        assertTrue(LoopbackHosts.isLoopback("[0:0:0:0:0:0:0:1]"))
        assertFalse(LoopbackHosts.isLoopback("[localhost]"))
        assertFalse(LoopbackHosts.isLoopback("[127.0.0.1]"))
    }
}
