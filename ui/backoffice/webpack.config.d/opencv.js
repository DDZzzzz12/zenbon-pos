// @techstark/opencv-js is an Emscripten UMD that mentions Node's fs/path/crypto in a branch that is
// never taken in a browser. Without these fallbacks webpack 5 refuses to resolve them.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    fs: false,
    path: false,
    crypto: false,
});
