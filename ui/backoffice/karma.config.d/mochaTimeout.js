// Mocha's default per-test timeout (2 s) is shorter than the first, cold load of PDF.js in a full
// :ui-backoffice:wasmJsBrowserTest sweep: PdfJsPropagationTest has to fetch the lazily split PDF.js chunk and
// start its module worker before the first document opens, which took > 2 s under ChromeHeadless.
// kotlin.test has no per-test timeout, and runTest's own (60 s) never fires before mocha's, so the limit is
// raised here for the whole browser suite. A genuinely hanging test still fails, after 15 s.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 15000 }),
    }),
});
