// Build the film bundle.
//   node tools/build.mjs                      → dist/ (minified: exosuit.js, index.html, artifact.html)
//   node tools/build.mjs --out=.build/x --dev → unminified dev build
//   --only=head,arms   keep only these suit regions real; every other region becomes its
//                      envelope proxy (isolates you from other people's half-written files)
//   --stub=kit,post    swap these shared modules for their original placeholders in src/_stubs
//                      (materials, envmap, kit, hangar, choreo, post, hud) — use when someone
//                      else's module is mid-edit and breaks your build
import esbuild from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REGIONS = ['head', 'torso', 'arms', 'hands', 'legs', 'back'];

const STUBBABLE = ['materials', 'envmap', 'kit', 'hangar', 'choreo', 'post', 'hud'];

export async function build({ out = 'dist', dev = false, only = null, stub = null } = {}) {
  const outDir = path.resolve(ROOT, out);
  fs.mkdirSync(outDir, { recursive: true });
  const proxyPlugin = {
    name: 'region-proxy',
    setup(b) {
      if (stub && stub.length) {
        b.onResolve({ filter: /\/(materials|envmap|kit|hangar|choreo|post|hud)\.js$/ }, (args) => {
          if (args.importer.includes(path.sep + '_stubs' + path.sep)) return null;
          const name = path.basename(args.path, '.js');
          if (!stub.includes(name)) return null;
          return { path: path.join(ROOT, 'src', '_stubs', name + '.js') };
        });
      }
      if (!only) return;
      b.onResolve({ filter: /^\.\/(head|torso|arms|hands|legs|back)\.js$/ }, (args) => {
        if (!args.importer.endsWith(path.join('suit', 'index.js'))) return null;
        const name = args.path.slice(2, -3);
        if (only.includes(name)) return null;
        return { path: name, namespace: 'region-proxy' };
      });
      b.onLoad({ filter: /.*/, namespace: 'region-proxy' }, (args) => ({
        contents: `import { proxyRegion } from './proxy.js';\nexport default (ctx) => proxyRegion(${JSON.stringify(args.path)}, ctx);`,
        resolveDir: path.join(ROOT, 'src', 'suit'),
      }));
    },
  };
  const result = await esbuild.build({
    entryPoints: [path.join(ROOT, 'src', 'main.js')],
    bundle: true,
    format: 'iife',
    target: 'es2020',
    minify: !dev,
    sourcemap: dev ? 'inline' : false,
    outfile: path.join(outDir, 'exosuit.js'),
    plugins: [proxyPlugin],
    logLevel: 'silent',
    legalComments: 'none',
  });
  const fragment = fs.readFileSync(path.join(ROOT, 'src', 'page.html'), 'utf8');
  // artifact.html is the bare fragment (the Artifact host wraps it); index.html is standalone.
  fs.writeFileSync(path.join(outDir, 'artifact.html'), fragment);
  fs.writeFileSync(
    path.join(outDir, 'index.html'),
    `<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n<style>body{margin:0;font:14px system-ui,sans-serif}[hidden]{display:none!important}</style>\n</head>\n<body>\n${fragment}\n</body>\n</html>\n`,
  );
  return { outDir, warnings: result.warnings, bytes: fs.statSync(path.join(outDir, 'exosuit.js')).size };
}

export function parseArgs(argv) {
  const o = {};
  for (const a of argv) {
    const m = /^--([\w-]+)(?:=(.*))?$/.exec(a);
    if (m) o[m[1]] = m[2] === undefined ? true : m[2];
  }
  return o;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const a = parseArgs(process.argv.slice(2));
  const only = typeof a.only === 'string' ? a.only.split(',') : null;
  if (only) for (const r of only) if (!REGIONS.includes(r)) { console.error(`unknown region "${r}" — use ${REGIONS.join(', ')}`); process.exit(2); }
  const stub = typeof a.stub === 'string' ? a.stub.split(',') : null;
  if (stub) for (const r of stub) if (!STUBBABLE.includes(r)) { console.error(`unknown module "${r}" — use ${STUBBABLE.join(', ')}`); process.exit(2); }
  try {
    const r = await build({ out: a.out || 'dist', dev: !!a.dev, only, stub });
    console.log(`built ${path.relative(ROOT, r.outDir)}/exosuit.js  ${(r.bytes / 1024).toFixed(0)} KB${only ? '  (real regions: ' + only.join(',') + ')' : ''}`);
  } catch (err) {
    console.error('BUILD FAILED\n' + (err.errors ? err.errors.map((e) => `${e.location ? e.location.file + ':' + e.location.line + ':' + e.location.column + ' ' : ''}${e.text}`).join('\n') : err.stack));
    process.exit(1);
  }
}
