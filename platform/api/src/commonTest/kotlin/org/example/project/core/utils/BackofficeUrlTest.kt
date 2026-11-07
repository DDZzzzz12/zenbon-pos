package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The base URL is typed by hand at pairing and arrives with and without a trailing slash;
 * `LocalDatabaseManager.getApiBaseUrl()` normalises to WITH, but the helper must not depend on it.
 */
class BackofficeUrlTest {
    @Test
    fun base_url_with_trailing_slash() = assertEquals("http://192.168.1.10:8080/admin/", BackofficeUrl.from("http://192.168.1.10:8080/"))

    @Test
    fun base_url_without_trailing_slash() = assertEquals("http://192.168.1.10:8080/admin/", BackofficeUrl.from("http://192.168.1.10:8080"))

    @Test
    fun base_url_with_a_path_prefix() = assertEquals("https://pos.example.com/kasse/admin/", BackofficeUrl.from("https://pos.example.com/kasse/"))

    @Test
    fun base_url_with_a_port_and_surrounding_whitespace() =
        assertEquals("https://pos.example.com:8443/admin/", BackofficeUrl.from(" https://pos.example.com:8443 "))

    @Test
    fun several_trailing_slashes_collapse_to_one() = assertEquals("http://host:8080/admin/", BackofficeUrl.from("http://host:8080///"))
}
