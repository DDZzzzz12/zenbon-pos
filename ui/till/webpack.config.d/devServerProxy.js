// Proxy API requests to the POS server during development.
//
// This works because the wasm client defaults its API base URL to the page's own origin (see
// DefaultApiBaseUrl.wasmJs.kt). The page is served from :8082, so it asks :8082 for /api, and this
// proxy forwards that to pos-server on :8080 -- same origin from the browser's point of view, so no
// CORS is involved and pos-server needs no CORS plugin.
//
// An earlier attempt at this file was reverted because the client then used an absolute
// http://localhost:8080/ base URL: it never requested a relative path, so a path proxy could not
// help, and every call failed the cross-origin preflight instead.
//
// NOTE: no '/ws' proxy. '/ws' is webpack-dev-server's own HMR path and proxying it hijacks hot
// reload. The till does not need one: change notifications arrive as Server-Sent Events on '/api'
// paths (see NotificationService), not over a raw WebSocket.
//
// '/font' serves the CJK font pos-server hosts for the language switcher -- without it, selecting
// Chinese renders tofu locally while working once deployed.
if (config.devServer) {
    config.devServer.proxy = [
        {
            context: ['/api', '/font'],
            target: 'http://localhost:8080',
            changeOrigin: true,
        },
    ];
}
