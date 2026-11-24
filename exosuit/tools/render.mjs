// Offline renderer: steps the film frame by frame in headless Chromium and encodes a video.
//
//   node tools/render.mjs --size=3840x2160 --fps=60 --out=out/exosuit_4k60.mp4
//
//   --size=WxH      output resolution (default 1920x1080)
//   --fps=N         frames per second (default 60)
//   --from=s --to=s time range in seconds (default 0..20)
//   --workers=N     parallel browser pages (default 2)
//   --crf=N         x264 quality (default 15; lower = better/larger)
//   --frames=dir    where PNG frames are kept (default out/frames_<WxH>_<fps>); existing frames are
//                   skipped, so an interrupted render resumes where it stopped
//   --keep          keep the PNG frames after encoding
//   --no-encode     only render frames
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { build, parseArgs, ROOT } from './build.mjs';

const require = createRequire('/opt/node-tools/node_modules/');
const { chromium } = require('playwright');

const a = parseArgs(process.argv.slice(2));
const [W, H] = String(a.size || '1920x1080').split('x').map((n) => parseInt(n, 10));
const fps = parseInt(a.fps || '60', 10);
const from = parseFloat(a.from ?? '0');
const to = parseFloat(a.to ?? '20');
const workers = parseInt(a.workers || '2', 10);
const out = path.resolve(ROOT, a.out || `out/exosuit_${H}p${fps}.mp4`);
const framesDir = path.resolve(ROOT, a.frames || `out/frames_${W}x${H}_${fps}`);
fs.mkdirSync(framesDir, { recursive: true });
fs.mkdirSync(path.dirname(out), { recursive: true });

const built = await build({ out: '.build/render', dev: false });
const url = pathToFileURL(path.join(built.outDir, 'index.html')).href + '?capture=1';

const first = Math.round(from * fps);
const count = Math.round((to - from) * fps); // frame i shows time (first + i) / fps; the last frame is one step before `to`
const frameFile = (i) => path.join(framesDir, `f_${String(i).padStart(6, '0')}.png`);
const todo = [];
for (let i = 0; i < count; i++) if (!fs.existsSync(frameFile(i))) todo.push(i);
console.log(`${W}x${H} @ ${fps} fps, ${count} frames (${todo.length} to render), ${workers} worker(s)`);

let done = 0;
const t0 = Date.now();
async function worker(id) {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  page.on('pageerror', (e) => { console.error('pageerror: ' + e.message); process.exitCode = 1; });
  await page.goto(url);
  await page.waitForFunction(() => window.__exo && window.__exo.ready, null, { timeout: 180000 });
  for (;;) {
    const i = todo.shift();
    if (i === undefined) break;
    await page.evaluate((t) => window.__exo.renderAt(t), (first + i) / fps);
    const tmp = frameFile(i) + '.tmp';
    await page.screenshot({ path: tmp, type: 'png' });
    fs.renameSync(tmp, frameFile(i));
    done++;
    if (done % 20 === 0 || todo.length === 0) {
      const el = (Date.now() - t0) / 1000;
      console.log(`frame ${done}/${done + todo.length}  ${(el / done).toFixed(2)} s/frame  eta ${Math.round((el / done) * todo.length / 60)} min`);
    }
  }
  await browser.close();
}
if (todo.length) await Promise.all(Array.from({ length: Math.min(workers, todo.length) }, (_, i) => worker(i)));

if (!a['no-encode']) {
  const r = spawnSync('ffmpeg', ['-y', '-hide_banner', '-loglevel', 'error', '-framerate', String(fps), '-i', path.join(framesDir, 'f_%06d.png'),
    '-c:v', 'libx264', '-preset', 'slow', '-crf', String(a.crf || 15), '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
    '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', out], { stdio: 'inherit' });
  if (r.status !== 0) { console.error('ffmpeg failed'); process.exit(1); }
  console.log(`wrote ${path.relative(ROOT, out)}  ${(fs.statSync(out).size / 1e6).toFixed(1)} MB`);
  if (!a.keep) fs.rmSync(framesDir, { recursive: true, force: true });
}
