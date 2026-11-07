package org.example.project.admin.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiUrlTest {
    @Test
    fun a_root_relative_download_url_resolves_against_the_page_origin() {
        assertEquals(
            "http://pos.local:8080/api/documents/7/file",
            resolveApiUrl("/api/documents/7/file", baseUrl = "/", origin = "http://pos.local:8080"),
        )
    }

    @Test
    fun a_path_without_a_leading_slash_gets_exactly_one() {
        assertEquals(
            "https://pos.local/api/documents/7/file",
            resolveApiUrl("api/documents/7/file", baseUrl = "/", origin = "https://pos.local/"),
        )
    }

    @Test
    fun an_absolute_base_url_wins_over_the_origin() {
        assertEquals(
            "https://api.example/pos/api/documents/7/file",
            resolveApiUrl("/api/documents/7/file", baseUrl = "https://api.example/pos/", origin = "http://ignored"),
        )
    }

    @Test
    fun an_absolute_path_is_kept() {
        assertEquals(
            "https://cdn.example/a.pdf",
            resolveApiUrl("https://cdn.example/a.pdf", baseUrl = "/", origin = "http://pos.local"),
        )
    }
}
