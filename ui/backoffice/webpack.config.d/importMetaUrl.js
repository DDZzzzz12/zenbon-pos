// Keeps the build machine's absolute path out of the shipped bundle.
//
// Webpack compiles every bare `import.meta.url` into a string constant: the module's ABSOLUTE
// file:// URL on the machine that built it (file:///home/<user>/<checkout>/build/wasm/...). Three
// sources reach this bundle: pdf.js's Node-only `createRequire(import.meta.url)`, the Emscripten
// glue of pdf.js's JPX/JBIG2 decoders and skiko.mjs. None of them uses the value in a browser
// (Node-only branch, discarded `new URL(".", ...)`, and a locateFile fallback that is never taken),
// but the string still shipped to every customer: a user name and directory layout, and a bundle
// that differs per build machine.
//
// This plugin taps the same parser hook first and emits the module's path RELATIVE to the webpack
// context instead, as a file:// URL of the same shape (file:///../node_modules/x -> file:///node_modules/x).
// Only the emitted text changes: webpack's own evaluation of import.meta.url is left alone, so
// `new URL('x', import.meta.url)` assets and `new Worker(new URL(...))` (the pdf.js worker and its
// .wasm decoders) are still resolved and emitted exactly as before.
//
// Kept identical in ui/backoffice/ and ui/till/ webpack.config.d: webpack.config.d is read only from
// the project that builds the executable, so shared-ui (where PdfJsLoader lives) cannot carry it.
// The guard is the root `verifyWasmDistHasNoBuildPaths` task, run after every wasmJsBrowserDistribution.
;(function (config) {
    const path = require('path');
    const PLUGIN_NAME = 'RelativeImportMetaUrlPlugin';

    class RelativeImportMetaUrlPlugin {
        apply(compiler) {
            const { ConstDependency } = compiler.webpack.dependencies;
            const relativeUrl = (module) => {
                const relative = path.relative(compiler.context, module.resource).split(path.sep).join('/');
                return new URL(relative, 'file:///').href;
            };
            compiler.hooks.compilation.tap(PLUGIN_NAME, (compilation, { normalModuleFactory }) => {
                const handler = (parser) => {
                    // stage -10: before webpack's ImportMetaPlugin (stage 0), whose tap would emit the absolute URL.
                    parser.hooks.expression.for('import.meta.url').tap({ name: PLUGIN_NAME, stage: -10 }, (expr) => {
                        const dep = new ConstDependency(JSON.stringify(relativeUrl(parser.state.module)), expr.range);
                        dep.loc = expr.loc;
                        parser.state.module.addPresentationalDependency(dep);
                        return true;
                    });
                };
                for (const type of ['javascript/auto', 'javascript/esm']) {
                    normalModuleFactory.hooks.parser.for(type).tap(PLUGIN_NAME, handler);
                }
            });
        }
    }

    config.plugins = config.plugins || [];
    config.plugins.push(new RelativeImportMetaUrlPlugin());
})(config);
