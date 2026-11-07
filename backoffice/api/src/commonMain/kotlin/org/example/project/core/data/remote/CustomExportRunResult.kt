package org.example.project.core.data.remote

import org.example.project.core.network.FileDownload

/**
 * A finished custom-export run: the CSV, plus what the server said *about* it.
 *
 * The two extra facts arrive as response headers (`X-Export-Row-Count`,
 * `X-Export-Truncated`) because they cannot ride in a body that is the file itself.
 * They are wrapped here rather than added to [FileDownload] because they are not
 * general download metadata — no other download in this app has a row count — and
 * putting them there would give every caller two fields only one endpoint ever sets.
 *
 * [truncated] is not an error: the file is still useful. But an operator who is never
 * told is an operator who reconciles against a silently short list, which is precisely
 * the failure the server sends the header to prevent. A caller must surface it.
 *
 * [rowCount] is null when the header was absent or unparseable — an older server, or a
 * proxy that stripped it. Null means "unknown", never "zero".
 */
data class CustomExportRunResult(
    val download: FileDownload,
    val rowCount: Int?,
    val truncated: Boolean,
)
