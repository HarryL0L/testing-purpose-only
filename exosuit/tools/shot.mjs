// Render still frames of the film in headless Chromium (software WebGL) and save PNGs.
//
//   node tools/shot.mjs --name=<you> --t=0,5.2,8 [options]
//
//   --name=<id>        REQUIRED. Your private build + output namespace (.build/<id>, shots/<id>).
//   --t=a,b,c          film times in seconds (default 0). Each becomes one PNG.
//   --only=head,arms   real suit regions; all others are swapped for grey envelope proxies.
//                      Omit to build everything for real.
//   --stub=kit,post    swap shared modules (materials, envmap, kit, hangar, choreo, post, hud) for their
//                      original placeholders — use when someone else's half-edited file breaks your build
//   --solo=head        hide every suit region except these.
//   --cam=az,el,dist,tx,ty,tz,fov   fixed debug camera instead of the film camera. Degrees/metres,
//                      world space. az 0 = straight in front of the suit (+Z), 90 = suit's left (+X),
//                      180 = behind. Target defaults 0,1,0; fov defaults 30.
//   --force=k:v,...    override state values, e.g. explode:1  helmet:0.5  hero:1  presentHand:1
//                      (section names take the raw 0..1 progress through that section)
//   --demo=kit|mat     show kit.buildDemo() / materials buildDemo() on the platform instead of the suit
//   --noenv            hide the hangar (flat grey backdrop, lights stay) — best for inspecting parts
//   --nopost           bypass post-processing (no bloom / depth of field)
//   --size=1280x720    output size (default 1280x720)
//   --tag=front        filename prefix (default "shot")
//   --sheet            also write one contact sheet PNG of all frames (cheap to look at)
//   --info             print draw calls / triangle count
//
// Output: shots/<name>/<tag>_t<seconds*100>.png  (+ <tag>_sheet.png). Exit code 1 if the page
// threw an error or logged console errors — fix those before trusting the picture.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { build, parseArgs, ROOT } from './build.mjs';

const require = createRequire('/opt/node-tools/node_modules/');
const { chromium } = require('playwright');

const a = parseArgs(process.argv.slice(2));
if (!a.name || a.name === true) { console.error('--name=<id> is required'); process.exit(2); }
const times = String(a.t ?? '0').split(',').map(parseFloat);
const [W, H] = String(a.size || '1280x720').split('x').map((n) => parseInt(n, 10));
const tag = a.tag || 'shot';
const outDir = path.join(ROOT, 'shots', a.name);
fs.mkdirSync(outDir, { recursive: true });

let built;
try {
  built = await build({ out: path.join('.build', a.name), dev: true, only: typeof a.only === 'string' ? a.only.split(',') : null, stub: typeof a.stub === 'string' ? a.stub.split(',') : null });
} catch (err) {
  console.error('BUILD FAILED\n' + (err.errors ? err.errors.map((e) => `${e.location ? e.location.file + ':' + e.location.line + ':' + e.location.column + ' ' : ''}${e.text}`).join('\n') : err.stack));
  process.exit(1);
}

const q = new URLSearchParams({ capture: '1' });
for (const k of ['cam', 'force', 'solo', 'demo']) if (typeof a[k] === 'string') q.set(k, a[k]);
if (a.noenv) q.set('noenv', '1');
if (a.nopost) q.set('post', '0');
const url = pathToFileURL(path.join(built.outDir, 'index.html')).href + '?' + q.toString();

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
const problems = [];
page.on('pageerror', (e) => problems.push('pageerror: ' + (e.stack || e.message)));
page.on('console', (m) => { if (m.type() === 'error') problems.push('console.error: ' + m.text()); else if (m.type() === 'warning' && !/GPU stall|GL Driver Message/.test(m.text())) console.log('[page warn] ' + m.text().slice(0, 300)); });
await page.goto(url);
try {
  await page.waitForFunction(() => window.__exo && window.__exo.ready, null, { timeout: 120000 });
} catch (e) {
  problems.push('page never became ready (window.__exo missing) — see errors above');
}
const files = [];
if (!problems.some((p) => p.includes('never became ready'))) {
  for (const t of times) {
    const t0 = Date.now();
    await page.evaluate((t) => window.__exo.renderAt(t), t);
    const file = path.join(outDir, `${tag}_t${String(Math.round(t * 100)).padStart(4, '0')}.png`);
    await page.screenshot({ path: file });
    files.push({ t, file });
    console.log(`${path.relative(ROOT, file)}  (${Date.now() - t0} ms)`);
  }
  if (a.info) console.log('info ' + JSON.stringify(await page.evaluate(() => window.__exo.info())));
}
if (a.sheet && files.length > 1) {
  const cols = Math.min(files.length, files.length <= 4 ? 2 : files.length <= 9 ? 3 : 4);
  const cw = Math.round(1920 / cols), ch = Math.round((cw * H) / W);
  const rows = Math.ceil(files.length / cols);
  const html = `<body style="margin:0;background:#000;display:grid;grid-template-columns:repeat(${cols},${cw}px);font:600 15px monospace;color:#fff">${files
    .map((f) => `<div style="position:relative;height:${ch}px"><img src="${pathToFileURL(f.file).href}" style="width:${cw}px;height:${ch}px;display:block"><span style="position:absolute;left:6px;top:4px;background:#000a;padding:1px 5px">t=${f.t.toFixed(2)}s</span></div>`)
    .join('')}</body>`;
  const sheetPage = await browser.newPage({ viewport: { width: cw * cols, height: ch * rows } });
  const tmp = path.join(outDir, `${tag}_sheet.html`);
  fs.writeFileSync(tmp, html);
  await sheetPage.goto(pathToFileURL(tmp).href);
  await sheetPage.waitForLoadState('load');
  const sheet = path.join(outDir, `${tag}_sheet.png`);
  await sheetPage.screenshot({ path: sheet });
  fs.unlinkSync(tmp);
  console.log(`${path.relative(ROOT, sheet)}  (contact sheet ${cols}x${rows})`);
}
await browser.close();
if (problems.length) {
  console.error('\nPAGE PROBLEMS:\n' + [...new Set(problems)].slice(0, 12).join('\n'));
  process.exit(1);
}
