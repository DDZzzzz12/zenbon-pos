package org.example.project.core.utils

/**
 * The web admin's URL, derived from the server base URL the till stores at pairing.
 *
 * pos-server serves the admin panel at `/admin/`. The base URL is typed by hand and arrives with
 * or without a trailing slash (and, behind a reverse proxy, with a path prefix), so this is the one
 * place that normalises it. Used by the "Backoffice öffnen" footer action on the till and on an
 * order station (order-station shell design §3.5).
 */
object BackofficeUrl {
    private const val ADMIN_PATH = "admin/"

    /** `<base>/admin/`, with any number of trailing slashes on [baseUrl] collapsed to one. */
    fun from(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/" + ADMIN_PATH
}
