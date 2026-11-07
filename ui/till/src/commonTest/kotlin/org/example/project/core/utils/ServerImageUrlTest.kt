package org.example.project.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerImageUrlTest {
    @Test
    fun baseWithoutTrailingSlashAndPathWithoutLeadingSlash() {
        assertEquals(
            "http://10.0.0.5:8080/api/shop-info/logo/a.jpg",
            resolveServerImageUrl("http://10.0.0.5:8080", "api/shop-info/logo/a.jpg"),
        )
    }

    @Test
    fun baseWithTrailingSlash() {
        assertEquals(
            "http://10.0.0.5:8080/api/shop-info/logo/a.jpg",
            resolveServerImageUrl("http://10.0.0.5:8080/", "api/shop-info/logo/a.jpg"),
        )
    }

    @Test
    fun pathWithLeadingSlashDoesNotDoubleUp() {
        assertEquals(
            "http://h/api/x.png",
            resolveServerImageUrl("http://h/", "/api/x.png"),
        )
    }

    @Test
    fun absoluteAndFileUrlsPassThrough() {
        assertEquals("https://cdn.example/x.png", resolveServerImageUrl("http://h", "https://cdn.example/x.png"))
        assertEquals("file:///tmp/x.png", resolveServerImageUrl("http://h", "file:///tmp/x.png"))
    }

    @Test
    fun nullBlankOrMissingBaseGiveNull() {
        assertNull(resolveServerImageUrl("http://h", null))
        assertNull(resolveServerImageUrl("http://h", "  "))
        assertNull(resolveServerImageUrl(null, "api/x.png"))
        assertNull(resolveServerImageUrl("", "api/x.png"))
    }
}
