// Proxy API requests to the POS server during development.
// NOTE: no '/ws' proxy — the admin panel opens no WebSocket, and '/ws' is
// webpack-dev-server's own HMR path; proxying it to pos-server hijacks hot reload.
// When the admin gains real WS features, add a proxy on a specific app path
// (e.g. '/api/ws') and point webpack's HMR client elsewhere.
// '/font' serves the CJK font pos-server hosts for the language switcher. In production both the
// panel and the font come from pos-server, so this only restores in dev what is already true there —
// without it, selecting Chinese renders tofu locally while working once deployed.
if (config.devServer) {
    config.devServer.proxy = [
        {
            context: ['/api', '/font'],
            target: 'http://localhost:8080',
            changeOrigin: true,
        },
    ];
}
