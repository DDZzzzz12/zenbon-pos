// Writes <file>.br and <file>.gz next to every compressible file under a directory, so pos-server can
// serve them as-is (Ktor staticResources { preCompressed(...) }) instead of gzipping per request.
// A sibling is written only when it is smaller than the original. Usage: node precompress.mjs <dir>
import { brotliCompressSync, gzipSync, constants } from 'node:zlib';
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const MIN_BYTES = 1024;
const COMPRESSIBLE = new Set(['.js', '.mjs', '.wasm', '.html', '.json', '.css', '.svg', '.txt', '.ttf', '.otf', '.xml']);

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) yield* walk(path);
    else yield path;
  }
}

const root = process.argv[2];
if (!root) throw new Error('usage: precompress.mjs <dir>');

let files = 0;
for (const path of walk(root)) {
  if (!COMPRESSIBLE.has(extname(path).toLowerCase())) continue;
  const data = readFileSync(path);
  if (data.length < MIN_BYTES) continue;
  const brotli = brotliCompressSync(data, {
    params: { [constants.BROTLI_PARAM_QUALITY]: 11, [constants.BROTLI_PARAM_SIZE_HINT]: data.length },
  });
  const gzip = gzipSync(data, { level: 9 });
  if (brotli.length < data.length) writeFileSync(`${path}.br`, brotli);
  if (gzip.length < data.length) writeFileSync(`${path}.gz`, gzip);
  files++;
}
console.log(`precompressed ${files} files under ${root}`);
