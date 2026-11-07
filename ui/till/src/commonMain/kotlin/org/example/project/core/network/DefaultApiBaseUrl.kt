package org.example.project.core.network

/**
 * Where this client talks to the server when the operator has not configured a URL.
 *
 * Only a default: a stored `API_URL` preference always wins, so an operator who pointed the till at
 * a particular server is never overridden.
 *
 * The default is per platform because the platforms genuinely differ. A desktop or Android till is
 * a separate device on the shop network and must be told where the server is, so it starts at the
 * conventional local address. The browser preview is **served by the server itself** at
 * `/pos`, so its own origin is the answer by construction — and stays right if pos-server
 * moves to another host, another port, or behind TLS.
 *
 * Hard-coding `http://localhost:8080/` everywhere worked only by luck: it happened to match when
 * the server ran on the same machine on the default port.
 */
expect fun defaultApiBaseUrl(): String
