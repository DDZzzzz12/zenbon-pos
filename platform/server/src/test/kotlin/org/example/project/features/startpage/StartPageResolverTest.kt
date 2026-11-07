package org.example.project.features.startpage

import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.setup.StartPageTarget.ADMIN
import org.example.project.core.domain.enums.setup.StartPageTarget.NONE
import org.example.project.core.domain.enums.setup.StartPageTarget.POS
import kotlin.test.Test
import kotlin.test.assertEquals

class StartPageResolverTest {
    private fun input(
        initialized: Boolean = true,
        force: Boolean = false,
        cookie: String? = null,
        server: StartPageTarget = NONE,
        available: Set<StartPageTarget> = setOf(ADMIN, POS),
    ) = StartPageInput(initialized, force, cookie, server, available)

    @Test
    fun `uninitialised server always goes to the admin for setup`() {
        assertEquals(StartPageDecision.Redirect("/admin/"), StartPageResolver.resolve(input(initialized = false)))
        assertEquals(
            StartPageDecision.Redirect("/admin/"),
            StartPageResolver.resolve(input(initialized = false, force = true, cookie = "pos")),
        )
    }

    @Test
    fun `query start shows the page regardless of defaults`() {
        assertEquals(
            StartPageDecision.Show(defaultUnavailable = false),
            StartPageResolver.resolve(input(force = true, cookie = "pos", server = ADMIN)),
        )
    }

    @Test
    fun `browser cookie wins over the server default`() {
        assertEquals(StartPageDecision.Redirect("/pos/"), StartPageResolver.resolve(input(cookie = "pos", server = ADMIN)))
    }

    @Test
    fun `server default applies without a cookie`() {
        assertEquals(StartPageDecision.Redirect("/admin/"), StartPageResolver.resolve(input(server = ADMIN)))
    }

    @Test
    fun `no defaults shows the page`() {
        assertEquals(StartPageDecision.Show(defaultUnavailable = false), StartPageResolver.resolve(input()))
    }

    @Test
    fun `unavailable cookie target falls through to the page with a note and keeps the cookie`() {
        val d = StartPageResolver.resolve(input(cookie = "pos", available = setOf(ADMIN)))
        assertEquals(StartPageDecision.Show(defaultUnavailable = true, clearCookie = false), d)
    }

    @Test
    fun `unavailable cookie target does not fall back to the server default`() {
        // The browser chose; honour that choice by explaining, not by silently sending it elsewhere.
        val d = StartPageResolver.resolve(input(cookie = "pos", server = ADMIN, available = setOf(ADMIN)))
        assertEquals(StartPageDecision.Show(defaultUnavailable = true), d)
    }

    @Test
    fun `unavailable server default shows the page with a note`() {
        assertEquals(
            StartPageDecision.Show(defaultUnavailable = true),
            StartPageResolver.resolve(input(server = POS, available = setOf(ADMIN))),
        )
    }

    @Test
    fun `forged cookie is ignored and cleared`() {
        assertEquals(
            StartPageDecision.Show(defaultUnavailable = false, clearCookie = true),
            StartPageResolver.resolve(input(cookie = "https://evil.example")),
        )
        assertEquals(
            StartPageDecision.Redirect("/admin/", clearCookie = true),
            StartPageResolver.resolve(input(cookie = "../../x", server = ADMIN)),
        )
    }

    @Test
    fun `cookie none means no browser default`() {
        assertEquals(
            StartPageDecision.Redirect("/admin/", clearCookie = true),
            StartPageResolver.resolve(input(cookie = "none", server = ADMIN)),
        )
    }
}
