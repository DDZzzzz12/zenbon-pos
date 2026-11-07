package org.example.project.core.utils

/** What [ServerUrlPolicy] says about a server URL an operator entered or a QR code carried. */
enum class ServerUrlVerdict {
    ACCEPTED,

    /** `http://` to a host other than loopback — readable by anyone on the shop network. */
    INSECURE_REMOTE,

    /**
     * No `http(s)://` scheme, no host, or an address that cannot be used as-is (credentials,
     * backslashes, whitespace or control characters, an invalid port).
     */
    MALFORMED,
}
