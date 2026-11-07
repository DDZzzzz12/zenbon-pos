package org.example.project.infrastructure.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.RequestConnectionPoint
import io.ktor.server.plugins.MutableOriginConnectionPoint
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaderValues
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ForwardedHeadersTest {
    // The Ktor test engine's peer is always "localhost", i.e. loopback: these requests stand in for Caddy.

    @Test
    fun `remote host is the client Caddy forwarded, not the proxy`() =
        testApplication {
            application {
                configureForwardedHeaders()
                routing { get("/whoami") { call.respondText(call.request.origin.remoteHost) } }
            }
            // The last entry is the value Caddy set (it replaces client-supplied X-Forwarded-For
            // rather than appending to it); anything before it came from the client anyway.
            val body = client.get("/whoami") { header("X-Forwarded-For", "203.0.113.9, 192.168.1.23") }.bodyAsText()
            assertEquals("192.168.1.23", body)
        }

    @Test
    fun `scheme is the one Caddy terminated`() =
        testApplication {
            application {
                configureForwardedHeaders()
                routing { get("/scheme") { call.respondText(call.request.origin.scheme) } }
            }
            val body = client.get("/scheme") { header("X-Forwarded-Proto", "https") }.bodyAsText()
            assertEquals("https", body)
        }

    @Test
    fun `no other forwarded header is read, even from loopback`() =
        testApplication {
            application {
                configureForwardedHeaders()
                routing {
                    get("/origin") {
                        val origin = call.request.origin
                        call.respondText(
                            "${origin.scheme} ${origin.serverHost} ${origin.serverPort} " +
                                "${origin.remoteHost} ${origin.remoteAddress}",
                        )
                    }
                }
            }
            val untouched = client.get("/origin").bodyAsText()
            val withOtherHeaders =
                client
                    .get("/origin") {
                        header("X-Forwarded-Protocol", "https")
                        header("X-Forwarded-SSL", "on")
                        header("Front-End-Https", "on")
                        header("X-Forwarded-Host", "evil.example")
                        header("X-Forwarded-Server", "evil.example")
                        // Ktor's default handler would Integer.parseInt this and fail the call.
                        header("X-Forwarded-Port", "not-a-port")
                        // The RFC 7239 plugin, not the X-Forwarded-* plugin installed here: never read either.
                        header("Forwarded", "for=10.9.9.9;proto=https;host=evil.example")
                    }.bodyAsText()
            assertEquals(untouched, withOtherHeaders)
            assertEquals("http", untouched.substringBefore(' '))
        }

    @Test
    fun `a LAN peer's forged headers change nothing`() {
        // ::ffff:127.0.0.1 is not trusted either: failing closed is fine, Netty reports plain literals.
        for (peer in listOf("192.168.1.50", "::ffff:127.0.0.1")) {
            val point = originFrom(peer)
            applyForwardedHeaders(point, forwarded(forHeader = "10.9.9.9", protoHeader = "https"))
            assertEquals(peer, point.remoteHost, "peer $peer")
            assertEquals(peer, point.remoteAddress, "peer $peer")
            assertEquals("http", point.scheme, "peer $peer")
        }
    }

    @Test
    fun `a loopback peer's last forwarded entry becomes the client`() {
        for (proxy in listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) {
            val point = originFrom(proxy)
            applyForwardedHeaders(point, forwarded(forHeader = "203.0.113.9, 192.168.1.23"))
            assertEquals("192.168.1.23", point.remoteHost, "peer $proxy")
            assertEquals("192.168.1.23", point.remoteAddress, "peer $proxy")
        }
    }

    @Test
    fun `a loopback peer without a usable forwarded entry stays the client`() {
        for (value in listOf(null, "", "   ", "203.0.113.9, ")) {
            val point = originFrom("127.0.0.1")
            applyForwardedHeaders(point, forwarded(forHeader = value))
            assertEquals("127.0.0.1", point.remoteHost, "X-Forwarded-For=<$value>")
            assertEquals("127.0.0.1", point.remoteAddress, "X-Forwarded-For=<$value>")
        }
    }

    @Test
    fun `only http or https is taken as the forwarded scheme`() {
        for (value in listOf("ftp", "garbage", "", "https, javascript")) {
            val point = originFrom("127.0.0.1")
            applyForwardedHeaders(point, forwarded(protoHeader = value))
            assertEquals("http", point.scheme, "X-Forwarded-Proto=<$value>")
        }
        for (value in listOf("HTTPS", "http, https")) {
            val point = originFrom("127.0.0.1")
            applyForwardedHeaders(point, forwarded(protoHeader = value))
            assertEquals("https", point.scheme, "X-Forwarded-Proto=<$value>")
        }
    }

    /**
     * The real Ktor origin the handler receives, wrapping a connection from [peer]; it reads through
     * to that connection until a field is assigned. Its constructor is Kotlin-`internal` but public on
     * the JVM, so it is built reflectively rather than re-implemented as a fake.
     */
    private fun originFrom(peer: String): MutableOriginConnectionPoint =
        MutableOriginConnectionPoint::class.java
            .getConstructor(RequestConnectionPoint::class.java)
            .newInstance(connectionFrom(peer))

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun connectionFrom(peer: String): RequestConnectionPoint =
        object : RequestConnectionPoint {
            override val scheme = "http"
            override val version = "HTTP/1.1"
            override val port = 8080
            override val localPort = 8080
            override val serverPort = 8080
            override val host = "localhost"
            override val localHost = "localhost"
            override val serverHost = "localhost"
            override val localAddress = "127.0.0.1"
            override val uri = "/"
            override val method = HttpMethod.Post
            override val remoteHost = peer
            override val remotePort = 50000
            override val remoteAddress = peer
        }

    private fun forwarded(
        forHeader: String? = null,
        protoHeader: String? = null,
    ) = XForwardedHeaderValues(
        protoHeader = protoHeader,
        forHeader = forHeader,
        hostHeader = null,
        httpsFlagHeader = null,
        portHeader = null,
    )
}
