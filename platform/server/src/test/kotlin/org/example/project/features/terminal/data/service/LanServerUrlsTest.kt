package org.example.project.features.terminal.data.service

import kotlin.test.Test
import kotlin.test.assertEquals

class LanServerUrlsTest {
    @Test
    fun `without a local CA devices reach the server on the port the request arrived on`() {
        assertEquals(
            listOf("http://192.168.1.20:8080", "http://10.0.0.5:8080"),
            LanServerUrls.urlsFor(listOf("192.168.1.20", "10.0.0.5"), port = 8080, tlsRootPresent = false),
        )
    }

    @Test
    fun `behind Caddy devices use https without a port`() {
        assertEquals(
            listOf("https://192.168.1.20"),
            LanServerUrls.urlsFor(listOf("192.168.1.20"), port = 8080, tlsRootPresent = true),
        )
    }

    @Test
    fun `a repeated address is offered once and no address gives no suggestion`() {
        assertEquals(
            listOf("http://192.168.1.20:8080"),
            LanServerUrls.urlsFor(listOf("192.168.1.20", "192.168.1.20"), port = 8080, tlsRootPresent = false),
        )
        assertEquals(emptyList(), LanServerUrls.urlsFor(emptyList(), port = 8080, tlsRootPresent = false))
    }
}
