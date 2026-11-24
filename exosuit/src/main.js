// Entry point: builds the scene, runs the player, exposes the deterministic capture API.
import * as THREE from 'three';
import { computeState, DURATION, FPS, SECTIONS } from './core/timeline.js';
import * as materialsModule from './render/materials.js';
import * as kit from './suit/kit.js';
import { createEnvironmentMap } from './render/envmap.js';
import { buildSuit } from './suit/index.js';
import { buildHangar } from './env/hangar.js';
import { createCameraRig } from './camera/choreo.js';
import { createPost } from './fx/post.js';
import { createHud } from './ui/hud.js';

const QUALITY = { auto: 0, '1080p': 1080, '1440p': 1440, '4k': 2160 };
const END_HOLD = 1.2; // seconds the last frame is held before the film loops

function parseParams() {
  const q = new URLSearchParams(location.search);
  const num = (k, d) => (q.has(k) ? parseFloat(q.get(k)) : d);
  const list = (k) => (q.get(k) ? q.get(k).split(',').filter(Boolean) : null);
  const force = {};
  for (const kv of list('force') || []) {
    const [k, v] = kv.split(':');
    force[k] = parseFloat(v);
  }
  return {
    capture: q.get('capture') === '1',
    t: num('t', null),
    cam: list('cam') ? list('cam').map(parseFloat) : null, // az,el,dist,tx,ty,tz,fov (degrees / metres, world space)
    post: q.get('post') !== '0',
    noenv: q.get('noenv') === '1',
    solo: list('solo'),
    force: Object.keys(force).length ? force : null,
    quality: q.get('quality') || 'auto',
    demo: q.get('demo'), // 'kit' | 'mat' → show that module's buildDemo() group instead of the suit
  };
}

function boot() {
  const params = parseParams();
  const stage = document.getElementById('stage');
  const frameEl = document.getElementById('frame');
  const canvas = document.getElementById('film');
  const overlay = document.getElementById('overlay');
  const hudRoot = document.getElementById('hud');

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance', preserveDrawingBuffer: params.capture });
  } catch (err) {
    stage.classList.add('no-webgl');
    const msg = document.getElementById('fallback');
    if (msg) msg.hidden = false;
    return;
  }
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  const mat = materialsModule.createMaterials({ renderer });
  scene.environment = createEnvironmentMap(renderer);

  const env = buildHangar({ THREE, mat, renderer });
  scene.add(env.group);
  if (env.fog) scene.fog = env.fog;
  scene.background = env.background || new THREE.Color(0x05070a);

  const suit = buildSuit({ mat });
  env.platform.add(suit.root);
  suit.root.traverse((o) => {
    if (o.isMesh) {
      if (o.userData.castShadow !== false) o.castShadow = true;
      if (o.userData.receiveShadow !== false) o.receiveShadow = true;
    }
  });
  if (params.solo) suit.solo(params.solo);
  if (params.demo) {
    // Debug stage for look-dev: ?demo=kit → kit.buildDemo(), ?demo=mat → materials buildDemo().
    const mod = params.demo === 'kit' ? kit : materialsModule;
    if (typeof mod.buildDemo === 'function') {
      const demo = mod.buildDemo({ THREE, mat, kit });
      demo.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; } });
      env.platform.add(demo);
      suit.root.visible = false;
    } else console.error(`?demo=${params.demo}: module has no buildDemo() export`);
  }
  if (params.noenv) {
    env.backdrop.visible = false;
    scene.fog = null;
    scene.background = new THREE.Color(0x16181c);
  }

  const cam = createCameraRig({ THREE, suit, env });
  const camera = cam.camera;
  const post = params.post ? createPost({ renderer, scene, camera }) : null;
  if (!post) renderer.toneMapping = THREE.ACESFilmicToneMapping;

  // ---- sizing ---------------------------------------------------------------------
  // The film is composed for 16:9 but tolerates 1:1 … 2.4:1; outside that it letterboxes.
  const size = { w: 0, h: 0, aspect: 16 / 9 };
  let quality = QUALITY[params.quality] !== undefined ? params.quality : 'auto';
  function resize() {
    const sw = stage.clientWidth || window.innerWidth;
    const sh = stage.clientHeight || window.innerHeight;
    const aspect = Math.min(2.4, Math.max(1.0, sw / sh));
    let cw = sw, ch = sw / aspect;
    if (ch > sh) { ch = sh; cw = sh * aspect; }
    frameEl.style.width = Math.round(cw) + 'px';
    frameEl.style.height = Math.round(ch) + 'px';
    let h;
    if (params.capture) h = Math.round(ch);
    else if (QUALITY[quality]) h = QUALITY[quality];
    else h = Math.round(ch * Math.min(window.devicePixelRatio || 1, 2));
    h = Math.min(h, 2160);
    let w = Math.round(h * aspect);
    if (w > 4096) { w = 4096; h = Math.round(w / aspect); }
    if (w === size.w && h === size.h) return;
    size.w = w; size.h = h; size.aspect = aspect;
    renderer.setPixelRatio(1);
    renderer.setSize(w, h, false);
    if (post) post.setSize(w, h);
  }

  // ---- frame ----------------------------------------------------------------------
  const frame = { suit, env, camera, renderer, scene, aspect: size.aspect, size };
  const dbg = { target: new THREE.Vector3() };
  let lastState = null;
  function renderAt(t) {
    const state = computeState(t);
    if (params.force) {
      for (const [k, v] of Object.entries(params.force)) {
        if (SECTIONS.includes(k)) { state.section[k] = v; state.active = k; }
        else state[k] = v;
        if (k === 'explode') state.forceExplode = v;
      }
    }
    frame.aspect = size.aspect;
    env.platform.rotation.y = state.turn;
    mat.update(state);
    scene.updateMatrixWorld(true);
    suit.update(state);
    scene.updateMatrixWorld(true);
    let shot;
    if (params.cam) {
      const [az = 30, el = 10, dist = 5, tx = 0, ty = 1, tz = 0, fov = 30] = params.cam;
      const a = (az * Math.PI) / 180, e = (el * Math.PI) / 180;
      dbg.target.set(tx, ty, tz);
      camera.position.set(tx + Math.sin(a) * Math.cos(e) * dist, ty + Math.sin(e) * dist, tz + Math.cos(a) * Math.cos(e) * dist);
      camera.fov = fov;
      camera.aspect = size.aspect;
      camera.lookAt(dbg.target);
      camera.updateProjectionMatrix();
      shot = { focus: dist, blur: 0, cut: false };
    } else {
      shot = cam.update(state, frame) || { focus: 5, blur: 0, cut: false };
    }
    env.update(state, frame);
    if (post) post.render(state, shot);
    else renderer.render(scene, camera);
    lastState = state;
    hud.update(state, player);
    return state;
  }

  // ---- player ---------------------------------------------------------------------
  const player = {
    duration: DURATION,
    fps: FPS,
    time: params.t ?? 0,
    playing: !params.capture && params.t === null,
    capture: params.capture,
    get quality() { return quality; },
    qualities: Object.keys(QUALITY),
    play() { if (player.time >= DURATION) player.time = 0; player.playing = true; },
    pause() { player.playing = false; },
    toggle() { player.playing ? player.pause() : player.play(); },
    seek(t) { player.time = Math.min(DURATION, Math.max(0, t)); if (!player.playing) renderAt(player.time); },
    setQuality(q) { if (QUALITY[q] !== undefined) { quality = q; resize(); if (!player.playing) renderAt(player.time); } },
    get renderSize() { return { w: size.w, h: size.h }; },
    get measuredFps() { return fpsAvg; },
  };
  const hud = createHud({ root: hudRoot, overlay, frame: frameEl, player });

  resize();
  window.addEventListener('resize', () => { resize(); if (!player.playing) renderAt(Math.min(player.time, DURATION)); });
  if (window.ResizeObserver) new ResizeObserver(() => { resize(); if (!player.playing) renderAt(Math.min(player.time, DURATION)); }).observe(stage);

  let last = performance.now();
  let fpsAvg = 60;
  function loop(now) {
    requestAnimationFrame(loop);
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (dt > 0) fpsAvg += (1 / dt - fpsAvg) * 0.05;
    if (!player.playing) return;
    player.time += dt;
    if (player.time >= DURATION + END_HOLD) player.time = 0;
    renderAt(Math.min(player.time, DURATION));
  }

  renderAt(Math.min(player.time, DURATION));
  if (!params.capture) requestAnimationFrame(loop);

  // Deterministic capture API used by tools/shot.mjs and tools/render.mjs.
  window.__exo = {
    ready: true, duration: DURATION, fps: FPS, player, suit, env, scene, camera, renderer,
    renderAt(t) { resize(); const s = renderAt(t); return { t: s.t, phase: s.phase, active: s.active }; },
    info() { return { calls: renderer.info.render.calls, triangles: renderer.info.render.triangles, panels: suit.panels.length, size: { ...size } }; },
  };
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();
