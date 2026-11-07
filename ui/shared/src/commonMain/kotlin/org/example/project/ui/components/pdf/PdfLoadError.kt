package org.example.project.ui.components.pdf

enum class PdfLoadError {
    Corrupt,
    PasswordProtected,
    Empty,
    NotPdf,

    /** The session could not be confirmed (HTTP 401, still 401 after one token refresh); signing in again may help. */
    Unauthorized,

    /** The server knows the user and refuses them this document (HTTP 403); asking again returns the same answer. */
    Forbidden,
    Network,

    /** The server has no file behind the URL (HTTP 404); asking again returns the same answer. */
    NotFound,
    Unsupported,
}
