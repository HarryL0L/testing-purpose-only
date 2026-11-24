// Hangar set — the dark engineering bay the suit is presented in.
//
// CONTRACT (unchanged from the placeholder):
//   buildHangar(ctx) → { group, platform, backdrop, fog, background, update(state, frame) }
//   group      everything, added to the scene by main.js
//   platform   THE ROTATING turntable top (Object3D at world origin, top surface at y = 0).
//              main.js parents the suit to it and sets platform.rotation.y = state.turn.
//   backdrop   sub-group holding architecture / haze / sparks that ?noenv=1 hides
//              (the lights, the turntable and its plinth are NOT inside it)
//   fog, background   scene fog / clear colour (main.js installs them)
//   update(state, frame)   called once per frame AFTER the camera has been placed.
//              Pure function of `state` (+ camera / frame.suit.snaps); nothing accumulates.
//
// LAYOUT (world space: +Y up, suit faces +Z, +X is the suit's left = screen right from the front)
//   turntable   rotating deck r 1.30 m (top y = 0) on a fixed plinth; hangar floor at y = -0.35
//   lights      6 real lights: warm KEY spot front / suit's-left (the only shadow caster),
//               RIM A ice-white spot back / suit's-right, RIM B teal spot back / suit's-left,
//               hemisphere fill, teal platform uplight (follows state.energy), lock-flash point
//               light (fires at frame.suit.snaps during reassembly). They mirror the softbox
//               layout baked into render/envmap.js so highlights and reflections agree.
//   keep-out    nothing between y 0.3 and 2.6 m is closer than 7.5 m to the suit axis (camera
//               volume); fixtures hang from the roof trusses above 2.7 m, trunking stays on the
//               floor.
//   haze        analytic light-shaft volumes for the four fixtures (exact in-scattering integral
//               through a cone, so they hold up from inside and outside), two drifting haze
//               shells, dust motes lit by the shafts.
//   sparks      closed-form ballistic streaks (drag + gravity + one bounce, pre-solved on the
//               CPU): panel-lock bursts at every suit snap, a background welding shower on a
//               loop, embers / detent sparks at the start of the hero phase.
import * as THREE from 'three';
import { ConvexGeometry } from 'three/examples/jsm/geometries/ConvexGeometry.js';
import * as TL from '../core/timeline.js';

const TAU = Math.PI * 2;
const DEG = Math.PI / 180;
const FLOOR_Y = -0.35;
const DECK_R = 1.3;
const AIM = [0, 1.15, 0]; // what the fixtures point at (suit chest)
const FIX = {
  key: { pos: [3.9, 4.5, 4.5], col: [1.0, 0.9, 0.78] },
  rimA: { pos: [-3.4, 3.15, -4.9], col: [0.74, 0.87, 1.0] },
  rimB: { pos: [4.3, 3.15, -4.4], col: [0.16, 1.0, 0.86] },
  top: { pos: [0.2, 6.3, 0.15], col: [0.72, 0.84, 1.0] },
};
const TEAL = [0.07, 1.0, 0.84];
const HALL = { x0: -13, x1: 13, z0: -11.5, z1: 13.5, top: 9.2 };
const WELD = { pos: [-8.3, 0.95, -6.9], period: 5.0, windows: [[0.25, 1.25], [2.85, 3.3]] };
const N_FLOOR_LAMPS = 8;
const MAX_SNAPS = 260;
const SHADOW_FROM_KEY = false;

// =====================================================================================
// small utilities
// =====================================================================================
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const _c = new THREE.Color();
/** sRGB hex → linear [r,g,b], optionally scaled (HDR for emitters). */
const lin = (hex, k = 1) => { _c.set(hex); return [_c.r * k, _c.g * k, _c.b * k]; };
const mul = (c, k) => [c[0] * k, c[1] * k, c[2] * k];
const pol = (r, az) => [r * Math.sin(az), r * Math.cos(az)]; // az 0 = +Z (front), 90° = +X

// ---- geometry buckets: everything static is appended to a few big buffers --------------
function makeBucket() {
  return { p: [], n: [], c: [], u: [], i: [], count: 0 };
}
const _v = new THREE.Vector3();
const _n = new THREE.Vector3();
const _nm = new THREE.Matrix3();
const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _e = new THREE.Euler();
const _s = new THREE.Vector3();
const _p = new THREE.Vector3();
const _a = new THREE.Vector3();
const _b = new THREE.Vector3();
const UP = new THREE.Vector3(0, 1, 0);
const UNIT_BOX = new THREE.BoxGeometry(1, 1, 1);

function addGeo(b, geo, m, col) {
  const pos = geo.attributes.position, nor = geo.attributes.normal, uv = geo.attributes.uv, idx = geo.index;
  _nm.getNormalMatrix(m);
  const base = b.count;
  for (let k = 0; k < pos.count; k++) {
    _v.fromBufferAttribute(pos, k).applyMatrix4(m);
    b.p.push(_v.x, _v.y, _v.z);
    _n.fromBufferAttribute(nor, k).applyMatrix3(_nm).normalize();
    b.n.push(_n.x, _n.y, _n.z);
    b.c.push(col[0], col[1], col[2]);
    if (uv) b.u.push(uv.getX(k), uv.getY(k)); else b.u.push(0, 0);
  }
  if (idx) for (let k = 0; k < idx.count; k++) b.i.push(base + idx.getX(k));
  else for (let k = 0; k < pos.count; k++) b.i.push(base + k);
  b.count += pos.count;
}
/** Quad from 4 corners in loop order; winding is fixed up to agree with the given normals. */
function quad(b, P, N, col, UV) {
  const base = b.count;
  let nx = 0, ny = 0, nz = 0;
  for (let k = 0; k < 4; k++) {
    const n = N.length === 4 ? N[k] : N;
    b.p.push(P[k][0], P[k][1], P[k][2]);
    b.n.push(n[0], n[1], n[2]);
    b.c.push(col[0], col[1], col[2]);
    if (UV) b.u.push(UV[k][0], UV[k][1]); else b.u.push(0, 0);
    nx += n[0]; ny += n[1]; nz += n[2];
  }
  const ux = P[1][0] - P[0][0], uy = P[1][1] - P[0][1], uz = P[1][2] - P[0][2];
  const vx = P[3][0] - P[0][0], vy = P[3][1] - P[0][1], vz = P[3][2] - P[0][2];
  const fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
  if (fx * nx + fy * ny + fz * nz >= 0) b.i.push(base, base + 1, base + 2, base, base + 2, base + 3);
  else b.i.push(base, base + 2, base + 1, base, base + 3, base + 2);
  b.count += 4;
}
function box(b, w, h, d, x, y, z, col, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1) {
  _m.compose(_p.set(x, y, z), _q.setFromEuler(_e.set(rx, ry, rz)), _s.set(w, h, d));
  addGeo(b, sx * sy * sz > 1 ? new THREE.BoxGeometry(1, 1, 1, sx, sy, sz) : UNIT_BOX, _m, col);
}
/** Box section running from A to B (w × d cross-section). */
function strut(b, ax, ay, az, bx, by, bz, w, d, col) {
  _a.set(ax, ay, az); _b.set(bx, by, bz);
  const len = _a.distanceTo(_b);
  _v.subVectors(_b, _a).normalize();
  _m.compose(_p.addVectors(_a, _b).multiplyScalar(0.5), _q.setFromUnitVectors(UP, _v), _s.set(w, len, d));
  addGeo(b, UNIT_BOX, _m, col);
}
/** Cylinder / cone frustum from A (radius r0) to B (radius r1). */
function tubeAB(b, ax, ay, az, bx, by, bz, r0, r1, col, seg = 10, open = false) {
  _a.set(ax, ay, az); _b.set(bx, by, bz);
  const len = _a.distanceTo(_b);
  _v.subVectors(_b, _a).normalize();
  _m.compose(_p.addVectors(_a, _b).multiplyScalar(0.5), _q.setFromUnitVectors(UP, _v), _s.set(1, len, 1));
  addGeo(b, new THREE.CylinderGeometry(r1, r0, 1, seg, 1, open), _m, col);
}
function cyl(b, r, h, x, y, z, col, seg = 16, rTop = r) {
  _m.compose(_p.set(x, y, z), _q.identity(), _s.set(1, h, 1));
  addGeo(b, new THREE.CylinderGeometry(rTop, r, 1, seg), _m, col);
}
/** Cable along a smooth path of [x,y,z] points. */
function cable(b, pts, r, col, radial = 6) {
  const curve = new THREE.CatmullRomCurve3(pts.map((p) => new THREE.Vector3(p[0], p[1], p[2])), false, 'centripetal');
  addGeo(b, new THREE.TubeGeometry(curve, Math.max(8, Math.round(curve.getLength() / 0.12)), r, radial, false), _m.identity(), col);
}
/**
 * Surface of revolution about +Y from a profile of [r, y] points, FLAT across profile segments
 * (crisp machined chamfers) and smooth around. Walk the profile inner → outer → down so the
 * normals face out. UVs are polar (u around, v along the profile) for the lathe-turned finish.
 */
function lathe(b, pts, col, seg = 120, a0 = 0, a1 = TAU) {
  let run = 0;
  for (let k = 0; k < pts.length - 1; k++) {
    const [r0, y0] = pts[k], [r1, y1] = pts[k + 1];
    const dr = r1 - r0, dy = y1 - y0, len = Math.hypot(dr, dy);
    if (len < 1e-6) continue;
    const nr = -dy / len, ny = dr / len;
    for (let j = 0; j < seg; j++) {
      const aa = a0 + ((a1 - a0) * j) / seg, ab = a0 + ((a1 - a0) * (j + 1)) / seg;
      const sa = Math.sin(aa), ca = Math.cos(aa), sb = Math.sin(ab), cb = Math.cos(ab);
      quad(b,
        [[r0 * sa, y0, r0 * ca], [r1 * sa, y1, r1 * ca], [r1 * sb, y1, r1 * cb], [r0 * sb, y0, r0 * cb]],
        [[nr * sa, ny, nr * ca], [nr * sa, ny, nr * ca], [nr * sb, ny, nr * cb], [nr * sb, ny, nr * cb]],
        col,
        [[(aa / TAU) * 24, (r0 + run) * 5], [(aa / TAU) * 24, (r1 + run + (dr === 0 ? len : 0)) * 5], [(ab / TAU) * 24, (r1 + run + (dr === 0 ? len : 0)) * 5], [(ab / TAU) * 24, (r0 + run) * 5]]);
    }
    if (dr === 0) run += len;
  }
}
/**
 * Machined deck plate: an annular sector with chamfered edges and side walls, top face at y.
 * `gap` is the full seam width left between neighbouring plates (constant along the edge).
 */
function ringPlate(b, r0, r1, a0, a1, y, th, bev, col, gap = 0) {
  const closed = a1 - a0 > TAU - 1e-4;
  const hasInner = r0 > 1e-4;
  const na = Math.max(2, Math.ceil((a1 - a0) / (2.5 * DEG)));
  const ri = hasInner ? r0 + bev : 0, ro = r1 - bev;
  const hg = gap / 2;
  // edge angle at radius r: `e` = 0 for the plate outline, bev for the inset top face
  const A0 = (r, e) => (closed ? a0 : a0 + (hg + e) / Math.max(r, 0.01));
  const A1 = (r, e) => (closed ? a1 : a1 - (hg + e) / Math.max(r, 0.01));
  const P = (r, a, yy) => [r * Math.sin(a), yy, r * Math.cos(a)];
  const UV = (r, a) => [(a / TAU) * 24, r * 5];
  const RAD = (a, s, up) => [Math.sin(a) * s, up, Math.cos(a) * s];
  const k = Math.SQRT1_2;
  for (let j = 0; j < na; j++) {
    const f0 = j / na, f1 = (j + 1) / na;
    const ai0 = TL.lerp(A0(ri, bev), A1(ri, bev), f0), ai1 = TL.lerp(A0(ri, bev), A1(ri, bev), f1);
    const ao0 = TL.lerp(A0(ro, bev), A1(ro, bev), f0), ao1 = TL.lerp(A0(ro, bev), A1(ro, bev), f1);
    const bo0 = TL.lerp(A0(r1, 0), A1(r1, 0), f0), bo1 = TL.lerp(A0(r1, 0), A1(r1, 0), f1);
    quad(b, [P(ri, ai0, y), P(ro, ao0, y), P(ro, ao1, y), P(ri, ai1, y)], [0, 1, 0], col, [UV(ri, ai0), UV(ro, ao0), UV(ro, ao1), UV(ri, ai1)]);
    // outer chamfer + wall
    quad(b, [P(ro, ao0, y), P(r1, bo0, y - bev), P(r1, bo1, y - bev), P(ro, ao1, y)], [RAD(ao0, k, k), RAD(bo0, k, k), RAD(bo1, k, k), RAD(ao1, k, k)], col, [UV(ro, ao0), UV(r1, bo0), UV(r1, bo1), UV(ro, ao1)]);
    quad(b, [P(r1, bo0, y - bev), P(r1, bo0, y - th), P(r1, bo1, y - th), P(r1, bo1, y - bev)], [RAD(bo0, 1, 0), RAD(bo0, 1, 0), RAD(bo1, 1, 0), RAD(bo1, 1, 0)], col, [UV(r1, bo0), UV(r1 + th, bo0), UV(r1 + th, bo1), UV(r1, bo1)]);
    if (hasInner) {
      const bi0 = TL.lerp(A0(r0, 0), A1(r0, 0), f0), bi1 = TL.lerp(A0(r0, 0), A1(r0, 0), f1);
      quad(b, [P(ri, ai0, y), P(r0, bi0, y - bev), P(r0, bi1, y - bev), P(ri, ai1, y)], [RAD(ai0, -k, k), RAD(bi0, -k, k), RAD(bi1, -k, k), RAD(ai1, -k, k)], col, [UV(ri, ai0), UV(r0, bi0), UV(r0, bi1), UV(ri, ai1)]);
      quad(b, [P(r0, bi0, y - bev), P(r0, bi0, y - th), P(r0, bi1, y - th), P(r0, bi1, y - bev)], [RAD(bi0, -1, 0), RAD(bi0, -1, 0), RAD(bi1, -1, 0), RAD(bi1, -1, 0)], col, [UV(r0, bi0), UV(r0 - th, bi0), UV(r0 - th, bi1), UV(r0, bi1)]);
    }
  }
  if (!closed) {
    for (const side of [0, 1]) {
      const AT = side ? A1 : A0, sgn = side ? 1 : -1;
      const am = side ? a1 : a0;
      const t = [Math.cos(am) * sgn, 0, -Math.sin(am) * sgn]; // outward tangent at this edge
      const tc = [t[0] * k, k, t[2] * k];
      quad(b, [P(ri, AT(ri, bev), y), P(ro, AT(ro, bev), y), P(r1, AT(r1, 0), y - bev), P(r0, AT(r0, 0), y - bev)], tc, col, [UV(ri, AT(ri, bev)), UV(ro, AT(ro, bev)), UV(r1, AT(r1, 0)), UV(r0, AT(r0, 0))]);
      quad(b, [P(r0, AT(r0, 0), y - bev), P(r1, AT(r1, 0), y - bev), P(r1, AT(r1, 0), y - th), P(r0, AT(r0, 0), y - th)], t, col, [UV(r0, am), UV(r1, am), UV(r1, am), UV(r0, am)]);
    }
  }
}
/** Flat annular sector (emissive inlays, painted marks). */
function ringFlat(b, r0, r1, a0, a1, y, col, na = 0) {
  na = na || Math.max(1, Math.ceil((a1 - a0) / (3 * DEG)));
  for (let j = 0; j < na; j++) {
    const aa = TL.lerp(a0, a1, j / na), ab = TL.lerp(a0, a1, (j + 1) / na);
    quad(b, [[r0 * Math.sin(aa), y, r0 * Math.cos(aa)], [r1 * Math.sin(aa), y, r1 * Math.cos(aa)], [r1 * Math.sin(ab), y, r1 * Math.cos(ab)], [r0 * Math.sin(ab), y, r0 * Math.cos(ab)]], [0, 1, 0], col);
  }
}
function finish(b, material, name, polarTangents = false) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(b.p, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(b.n, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(b.c, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(b.u, 2));
  if (b.bake) g.setAttribute('aBake', new THREE.Float32BufferAttribute(b.bake, 3));
  if (polarTangents) {
    // circumferential tangents about +Y: the anisotropic "lathe-turned" highlight needs a smooth
    // tangent field (UV-derived tangents are constant per triangle and show up as spokes)
    const tan = new Float32Array(b.count * 4);
    for (let i = 0; i < b.count; i++) {
      const x = b.p[i * 3], z = b.p[i * 3 + 2], r = Math.hypot(x, z) || 1;
      tan[i * 4] = z / r; tan[i * 4 + 2] = -x / r; tan[i * 4 + 3] = 1;
    }
    g.setAttribute('tangent', new THREE.BufferAttribute(tan, 4));
  }
  g.setIndex(b.i);
  g.computeBoundingSphere();
  const mesh = new THREE.Mesh(g, material);
  mesh.name = name;
  mesh.matrixAutoUpdate = false;
  return mesh;
}

// =====================================================================================
// procedural textures (seeded, generated once)
// =====================================================================================
/** Tiling fBm value noise, a different field in each of the four channels. */
function makeNoiseTexture(size, seed) {
  const data = new Uint8Array(size * size * 4);
  const acc = new Float32Array(size * size);
  for (let ch = 0; ch < 4; ch++) {
    const rnd = prng(seed + ch * 7919);
    acc.fill(0);
    let amp = 1, norm = 0;
    for (let o = 0; o < 5; o++) {
      const f = 4 << o;
      const lat = new Float32Array(f * f);
      for (let i = 0; i < lat.length; i++) lat[i] = rnd();
      const s = f / size;
      for (let y = 0; y < size; y++) {
        const v = y * s, y0 = Math.floor(v), fy = v - y0, wy = fy * fy * (3 - 2 * fy);
        const r0 = (y0 % f) * f, r1 = ((y0 + 1) % f) * f;
        for (let x = 0; x < size; x++) {
          const u = x * s, x0 = Math.floor(u), fx = u - x0, wx = fx * fx * (3 - 2 * fx);
          const c0 = x0 % f, c1 = (x0 + 1) % f;
          const a = lat[r0 + c0] + (lat[r0 + c1] - lat[r0 + c0]) * wx;
          const bb = lat[r1 + c0] + (lat[r1 + c1] - lat[r1 + c0]) * wx;
          acc[y * size + x] += amp * (a + (bb - a) * wy);
        }
      }
      norm += amp;
      amp *= 0.55;
    }
    for (let i = 0; i < acc.length; i++) {
      const v = (acc[i] / norm - 0.5) * 2.1 + 0.5;
      data[i * 4 + ch] = Math.max(0, Math.min(255, Math.round(v * 255)));
    }
  }
  const t = new THREE.DataTexture(data, size, size, THREE.RGBAFormat);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.magFilter = THREE.LinearFilter;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  t.generateMipmaps = true;
  t.needsUpdate = true;
  return t;
}
/**
 * Lathe-turned metal detail, laid out for polar UVs (u around the axis, v along the radius):
 * every texel row is one tool pass, so rows differ in tone and carry long streaks along u.
 * R = height (bump), G = roughness multiplier, B = metalness (1).
 */
function makeTurnedTexture(w, h, seed) {
  const rnd = prng(seed);
  const hgt = new Float32Array(w * h);
  let slow = 0.5;
  for (let y = 0; y < h; y++) {
    slow = slow * 0.9 + rnd() * 0.1; // broad tonal bands
    const tone = 0.55 * rnd() + 0.45 * (slow - 0.25) * 2;
    const f = 5 + Math.floor(rnd() * 9);
    const lat = [];
    for (let i = 0; i < f; i++) lat.push(rnd());
    const ph = rnd() * w;
    for (let x = 0; x < w; x++) {
      const u = (((x + ph) % w) / w) * f, i0 = Math.floor(u), fr = u - i0, wv = fr * fr * (3 - 2 * fr);
      const v = lat[i0 % f] + (lat[(i0 + 1) % f] - lat[i0 % f]) * wv;
      hgt[y * w + x] = TL.clamp01(0.62 * tone + 0.38 * v);
    }
  }
  const scr = new Float32Array(w * h);
  for (let k = 0; k < 90; k++) { // stray handling scratches
    let x = rnd() * w, y = rnd() * h;
    const ang = (rnd() - 0.5) * 2.4, len = 30 + rnd() * 160, deep = 0.35 + rnd() * 0.65;
    const dx = Math.cos(ang), dy = Math.sin(ang) * 0.6;
    for (let s = 0; s < len; s++) {
      const xi = ((Math.floor(x) % w) + w) % w, yi = ((Math.floor(y) % h) + h) % h;
      scr[yi * w + xi] = Math.max(scr[yi * w + xi], deep * Math.sin((s / len) * Math.PI));
      x += dx; y += dy;
    }
  }
  const data = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const hh = TL.clamp01(hgt[i] - scr[i] * 0.6);
    data[i * 4] = Math.round(hh * 255);
    data[i * 4 + 1] = Math.round(TL.clamp01(0.7 + 0.3 * hgt[i] + scr[i] * 0.25) * 255);
    data[i * 4 + 2] = 255;
    data[i * 4 + 3] = 255;
  }
  const t = new THREE.DataTexture(data, w, h, THREE.RGBAFormat);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.magFilter = THREE.LinearFilter;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  t.generateMipmaps = true;
  t.needsUpdate = true;
  return t;
}

// =====================================================================================
// materials
// =====================================================================================
/** Dark painted steel. `aBake` (per-vertex irradiance from the practical lamps) is added as light. */
function makePaintMaterial(uBakeGain, opts = {}) {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.35, roughness: 0.58, ...opts });
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uBakeGain = uBakeGain;
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nattribute vec3 aBake;\nvarying vec3 vBake;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvBake = aBake;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vBake;\nuniform float uBakeGain;')
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\ntotalEmissiveRadiance += vBake * uBakeGain * (diffuseColor.rgb * 0.6 + 0.004);');
  };
  return m;
}

/**
 * Hangar floor: sealed dark concrete. Panel joints, wear blotches and the painted keep-out ring
 * are procedural in the shader (crisp at any distance, no big textures). On top of the real
 * lights it evaluates a handful of "practical" lamps and the plinth's light ring with the same
 * BRDF, so every lamp in the set leaves a stretched reflection on the floor.
 */
function makeFloorMaterial(U, noise) {
  const m = new THREE.MeshStandardMaterial({ color: 0x1a1b1d, metalness: 0, roughness: 0.45 });
  m.defines = { STANDARD: '', NL: N_FLOOR_LAMPS };
  m.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, { uNoise: { value: noise }, uLampPos: U.lampPos, uLampCol: U.lampCol, uRingCol: U.ringLight });
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vWPos;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvWPos = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', /* glsl */ `#include <common>
        varying vec3 vWPos;
        uniform sampler2D uNoise;
        uniform vec3 uLampPos[NL];
        uniform vec3 uLampCol[NL];
        uniform vec3 uRingCol;
        float fJoint; float fGloss;
        float gridLine(vec2 p, float w) {
          vec2 g = abs(fract(p - 0.5) - 0.5);
          vec2 fw = fwidth(p);
          vec2 l = 1.0 - smoothstep(vec2(w), vec2(w) + fw * 1.5, g);
          return max(l.x, l.y) * (1.0 - smoothstep(0.02, 0.09, max(fw.x, fw.y)));
        }`)
      .replace('#include <color_fragment>', /* glsl */ `#include <color_fragment>
        {
          vec2 fp = vWPos.xz;
          vec4 n1 = texture2D(uNoise, fp * 0.083);
          vec4 n2 = texture2D(uNoise, fp * 0.71 + 0.31);
          float blotch = n1.r * 0.6 + n2.g * 0.4;
          fJoint = gridLine(fp / 4.0 + 0.5, 0.0035);
          fGloss = smoothstep(0.2, 0.8, n1.g * 0.45 + n2.r * 0.55);
          diffuseColor.rgb *= mix(0.75, 1.25, blotch);
          // tyre scuffs / drag marks: long soft streaks
          float scuff = smoothstep(0.62, 0.9, texture2D(uNoise, vec2(fp.x * 0.012 + fp.y * 0.004, fp.y * 0.21)).b);
          diffuseColor.rgb *= 1.0 - 0.35 * scuff;
          diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.0035), fJoint * 0.9);
          // painted keep-out ring around the turntable, worn through in patches
          float rr = length(fp);
          float aw = fwidth(rr) * 1.2;
          float band = 1.0 - smoothstep(0.04, 0.04 + aw, abs(rr - 2.62));
          float ang = atan(fp.x, fp.y);
          float dash = smoothstep(0.0, 0.02, abs(fract(ang * 24.0 / 6.2831853) - 0.5) - 0.14);
          float thin = 1.0 - smoothstep(0.008, 0.008 + aw, abs(rr - 2.42));
          float wear = smoothstep(0.3, 0.55, n2.b * 0.7 + n1.a * 0.5);
          diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.11, 0.075, 0.012), band * dash * wear * 0.8);
          diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.06, 0.065, 0.07), thin * wear * 0.7);
        }`)
      .replace('#include <roughnessmap_fragment>', /* glsl */ `#include <roughnessmap_fragment>
        roughnessFactor = clamp(mix(0.56, 0.3, fGloss) + fJoint * 0.4, 0.06, 1.0);`)
      .replace('#include <lights_fragment_end>', /* glsl */ `#include <lights_fragment_end>
        {
          IncidentLight fl;
          fl.visible = true;
          for (int i = 0; i < NL; i++) {
            vec3 lv = (viewMatrix * vec4(uLampPos[i], 1.0)).xyz - geometryPosition;
            float d2 = dot(lv, lv);
            fl.direction = lv * inversesqrt(d2);
            fl.color = uLampCol[i] / (d2 + 0.6);
            RE_Direct(fl, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight);
          }
          // plinth light ring: nearest point of the ring acts as the lamp
          vec2 rd = normalize(vWPos.xz + vec2(1e-4));
          vec3 rp = vec3(rd.x * 1.49, -0.12, rd.y * 1.49);
          vec3 lv = (viewMatrix * vec4(rp, 1.0)).xyz - geometryPosition;
          float d2 = dot(lv, lv);
          fl.direction = lv * inversesqrt(d2);
          fl.color = uRingCol / (d2 * 2.2 + sqrt(d2) * 0.5 + 0.02) * step(1.5, length(vWPos.xz));
          RE_Direct(fl, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight);
        }`);
  };
  return m;
}

// =====================================================================================
// turntable: rotating deck + fixed plinth
// =====================================================================================
function buildTurntable(env) {
  const { group, platform, U, tex } = env;
  const deck = makeBucket(), dark = makeBucket(), emis = makeBucket();
  const stator = makeBucket(), plinth = makeBucket(), ringE = makeBucket();
  const rnd = prng(0x7ab1e);

  const cDisc = lin(0x4d5158), cRim = lin(0x7b7f86), cBolt = lin(0xb4b8be), cDark = lin(0x0a0b0d);
  const cPaint = lin(0x23272d), cPaintD = lin(0x14171b), cPaintL = lin(0x3a4048);

  // ---- rotating deck --------------------------------------------------------------
  // centre plug + the big clean centre plate the suit stands on (r < 0.8 stays flat and empty)
  ringPlate(deck, 0, 0.03, 0, TAU, 0, 0.008, 0.0012, cRim);
  ringPlate(deck, 0.034, 0.8, 0, TAU, 0, 0.008, 0.002, cDisc);
  // twelve sector plates with seams and countersunk fasteners
  const NP = 12;
  for (let i = 0; i < NP; i++) {
    const a0 = (i / NP) * TAU, a1 = ((i + 1) / NP) * TAU;
    const tone = 0.78 + 0.3 * rnd();
    ringPlate(deck, 0.805, 1.148, a0, a1, 0, 0.008, 0.002, mul(cDisc, tone * 0.86), 0.005);
    for (const r of [0.85, 1.105]) for (const s of [0, 1]) {
      const a = s ? a1 - 0.05 / r : a0 + 0.05 / r;
      const [x, z] = pol(r, a);
      cyl(deck, 0.0085, 0.002, x, 0.0002, z, cBolt, 12);
      cyl(dark, 0.0045, 0.0005, x, 0.0014, z, cDark, 6);
    }
  }
  // sub-deck seen through the seams and the light channel
  cyl(dark, 1.296, 0.04, 0, -0.0265, 0, cDark, 96);
  // machined outer rim: channel wall, top land, chamfer, outer wall
  lathe(deck, [[1.2, -0.0065], [1.2, -0.0015], [1.2015, 0], [1.2875, 0], [1.3, -0.0125], [1.3, -0.066]], cRim, 144);
  lathe(dark, [[1.3, -0.066], [1.262, -0.066], [1.262, -0.13]], cDark, 96);
  // engraved index scale on the rim land: 72 ticks, long every 30°
  for (let i = 0; i < 72; i++) {
    const a = (i / 72) * TAU, long = i % 6 === 0;
    const [x, z] = pol(long ? 1.246 : 1.258, a);
    box(dark, 0.0028, 0.0006, long ? 0.05 : 0.026, x, 0.0002, z, cDark, 0, a, 0);
  }
  // teal light ring recessed in the channel: 72 segments, with a white "home" block at az 0
  for (let i = 0; i < 72; i++) {
    const a0 = (i / 72) * TAU + 0.6 * DEG, a1 = ((i + 1) / 72) * TAU - 0.6 * DEG;
    const home = i === 0 || i === 71;
    const dim = i % 6 === 2 || i % 6 === 3 ? 0.35 : 1; // dashed rhythm so rotation reads at a glance
    ringFlat(emis, 1.157, 1.193, a0, a1, -0.0042, home ? [1.6, 2.6, 2.5] : mul(TEAL, 1.0 * dim));
  }
  // index marks on the rim's outer wall (visible from low angles), triple mark at home
  for (let i = 0; i < 36; i++) {
    const marks = i === 0 ? [-1.6, 0, 1.6] : [0];
    for (const off of marks) {
      const a = (i / 36) * TAU + off * DEG;
      const [x, z] = pol(1.3008, a);
      box(emis, i % 3 === 0 ? 0.03 : 0.012, 0.016, 0.002, x, -0.04, z, mul(TEAL, i === 0 ? 1.6 : i % 3 === 0 ? 1.0 : 0.6), 0, a, 0);
    }
  }

  // ---- fixed plinth ---------------------------------------------------------------
  lathe(stator, [[1.276, -0.14], [1.276, -0.085], [1.468, -0.085], [1.494, -0.101], [1.494, -0.104], [1.484, -0.104]], mul(cDisc, 0.8), 144);
  lathe(ringE, [[1.484, -0.104], [1.484, -0.136]], [1, 1, 1], 96);
  lathe(plinth, [[1.484, -0.136], [1.532, -0.136], [1.532, -0.152], [1.7, -0.202], [1.8, -0.306], [1.84, -0.306], [1.84, FLOOR_Y]], cPaint, 144);
  for (let i = 0; i < 36; i++) { // stator fasteners
    const [x, z] = pol(1.43, ((i + 0.5) / 36) * TAU);
    cyl(stator, 0.009, 0.004, x, -0.083, z, cBolt, 8);
  }
  for (let i = 0; i < 24; i++) { // anchor bolts on the floor flange
    const [x, z] = pol(1.82, ((i + 0.5) / 24) * TAU);
    cyl(plinth, 0.013, 0.012, x, -0.3, z, cPaintL, 6);
  }
  // fixed index pointer at the front of the stator + a short scale
  box(stator, 0.05, 0.006, 0.07, 0, -0.082, 1.36, cPaintD);
  box(ringE, 0.012, 0.003, 0.05, 0, -0.0795, 1.36, [1.6, 1.6, 1.6]);
  // skirt: 12 panels separated by cover strips; vents on every third
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * TAU + 15 * DEG;
    const s = Math.sin(a), c = Math.cos(a);
    strut(plinth, 1.534 * s, -0.15, 1.534 * c, 1.7 * s, -0.199, 1.7 * c, 0.022, 0.006, cPaintD);
    strut(plinth, 1.7 * s, -0.2, 1.7 * c, 1.8 * s, -0.303, 1.8 * c, 0.022, 0.006, cPaintD);
    if (i % 3 === 1) {
      const am = a + 15 * DEG;
      for (let k = 0; k < 4; k++) {
        const r = 1.722 + k * 0.02, y = -0.222 - k * 0.0208;
        box(plinth, 0.34, 0.004, 0.008, r * Math.sin(am), y, r * Math.cos(am), cDark, -0.8, am, 0);
      }
    }
  }

  // ---- materials + meshes ---------------------------------------------------------
  const deckMat = new THREE.MeshPhysicalMaterial({
    vertexColors: true, metalness: 0.5, specularIntensity: 0, roughness: 0.58,
    roughnessMap: tex.turned, metalnessMap: tex.turned, bumpMap: tex.turned, bumpScale: 0.35,
    anisotropy: 0.5, anisotropyRotation: Math.PI / 2, // rougher across the tool marks → radial fan highlights
    envMapIntensity: 0.8,
  });
  const darkMat = new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.5, roughness: 0.7 });
  const emisMat = new THREE.MeshBasicMaterial({ vertexColors: true });
  const ringMat = new THREE.MeshBasicMaterial({ vertexColors: true });
  const plinthMat = makePaintMaterial(U.ringBake, { metalness: 0.55, roughness: 0.48 });

  // bake the light ring's spill onto the plinth paint (scaled by the ring level at runtime)
  plinth.bake = new Array(plinth.p.length).fill(0);
  for (let i = 0; i < plinth.count; i++) {
    const x = plinth.p[i * 3], y = plinth.p[i * 3 + 1], z = plinth.p[i * 3 + 2];
    const r = Math.hypot(x, z) || 1;
    const dx = (x / r) * 1.486 - x, dy = -0.12 - y, dz = (z / r) * 1.486 - z;
    const d = Math.hypot(dx, dy, dz) || 1;
    const nl = Math.max(0, (plinth.n[i * 3] * dx + plinth.n[i * 3 + 1] * dy + plinth.n[i * 3 + 2] * dz) / d);
    const e = (nl + 0.08) / (d * 6 + d * d * 30 + 0.02);
    plinth.bake[i * 3] = TEAL[0] * e; plinth.bake[i * 3 + 1] = TEAL[1] * e; plinth.bake[i * 3 + 2] = TEAL[2] * e;
  }

  const mDeck = finish(deck, deckMat, 'deck', true);
  const mDark = finish(dark, darkMat, 'deck-dark');
  const mEmis = finish(emis, emisMat, 'deck-lights');
  mDeck.receiveShadow = mDark.receiveShadow = true;
  // soft spill of the channel light across the neighbouring metal
  const spillGeo = new THREE.RingGeometry(1.06, 1.29, 96, 2);
  const sp = spillGeo.attributes.position, spc = new Float32Array(sp.count * 3);
  for (let i = 0; i < sp.count; i++) {
    const r = Math.hypot(sp.getX(i), sp.getY(i));
    const k = Math.abs(r - 1.175) < 0.03 ? 1 : 0;
    spc[i * 3] = TEAL[0] * k; spc[i * 3 + 1] = TEAL[1] * k; spc[i * 3 + 2] = TEAL[2] * k;
  }
  spillGeo.setAttribute('color', new THREE.BufferAttribute(spc, 3));
  spillGeo.rotateX(-Math.PI / 2);
  const spillMat = new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, fog: false });
  const mSpill = new THREE.Mesh(spillGeo, spillMat);
  mSpill.position.y = 0.0008;
  mSpill.renderOrder = 2;
  platform.add(mDeck, mDark, mEmis, mSpill);
  // contact occlusion under the boots (multiplied onto the deck; turns with the suit). The rims
  // skim the deck from behind, so without it the feet would not sit in the sheen.
  const aoMat = new THREE.MeshBasicMaterial({ vertexColors: true, blending: THREE.MultiplyBlending, transparent: true, depthWrite: false, fog: false, toneMapped: false });
  const aoDisc = (x, z, rx, rz, dark) => {
    const stops = [[0, dark], [0.4, dark + (1 - dark) * 0.12], [0.7, dark + (1 - dark) * 0.55], [0.88, dark + (1 - dark) * 0.88], [1, 1]];
    const pos = [], col = [], idx = [], N = 28;
    for (const [f, v] of stops) for (let i = 0; i < N; i++) { const a = (i / N) * TAU; pos.push(x + Math.sin(a) * rx * f, 0, z + Math.cos(a) * rz * f); col.push(v, v, v); }
    for (let k = 0; k < stops.length - 1; k++) for (let i = 0; i < N; i++) { const a = k * N + i, b = k * N + ((i + 1) % N); idx.push(a, b, a + N, b, b + N, a + N); }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    g.setIndex(idx);
    const m = new THREE.Mesh(g, aoMat);
    m.position.y = 0.0012;
    m.renderOrder = 1;
    platform.add(m);
  };
  aoDisc(0.144, 0.055, 0.125, 0.235, 0.36);
  aoDisc(-0.144, 0.055, 0.125, 0.235, 0.36);
  aoDisc(0, 0.03, 0.5, 0.52, 0.8);

  const mStator = finish(stator, deckMat, 'stator', true);
  const mPlinth = finish(plinth, plinthMat, 'plinth');
  const mRing = finish(ringE, ringMat, 'plinth-ring');
  mStator.receiveShadow = mPlinth.receiveShadow = true;
  group.add(mStator, mPlinth, mRing);

  return { deckMat, darkMat, emisMat, ringMat, spillMat, plinthMat, envMats: [deckMat, darkMat, plinthMat] };
}

// =====================================================================================
// architecture, fixtures, practicals
// =====================================================================================
function buildArchitecture(env) {
  const { backdrop, U, tex, glow, lamps } = env;
  const S = makeBucket(); // painted structure (receives baked practical light)
  const E = makeBucket(); // emitters (HDR vertex colours)
  const R = makeBucket(); // rubber: cables, hoses, mats
  const rnd = prng(0x4a96a2);
  const cD = lin(0x15181c), cS = lin(0x242930), cL = lin(0x3b424b), cY = lin(0x7a5c14), cR = lin(0x0b0c0d);
  const { x0: X0, x1: X1, z0: Z0, z1: Z1, top: CY } = HALL;
  const H = CY - FLOOR_Y;

  /** Register a practical: baked onto the structure, optionally reflected by the floor. */
  const lamp = (x, y, z, col, power, floor = 0, range = 9) => lamps.push({ x, y, z, r: col[0] * power, g: col[1] * power, b: col[2] * power, floor, range });
  const AMBER = [1.0, 0.42, 0.06], COOL = [0.72, 0.86, 1.0], WARM = [1.0, 0.72, 0.42], RED = [1.0, 0.07, 0.04], SODIUM = [1.0, 0.45, 0.1];
  const led = (x, y, z, col, k = 6, size = 0.035, blink = 0) => (blink ? glow(x, y, z, mul(col, k), size, 1, blink, rnd()) : glow(x, y, z, mul(col, k), size));

  // ---- shell: walls, ceiling ------------------------------------------------------
  const wall = (ax, az, bx, bz, nx, nz) => {
    const len = Math.hypot(bx - ax, bz - az), nu = Math.round(len / 1.3), nv = 8;
    for (let i = 0; i < nu; i++) {
      const tone = 0.8 + 0.4 * rnd();
      for (let j = 0; j < nv; j++) {
        const u0 = i / nu, u1 = (i + 1) / nu, y0 = FLOOR_Y + (H * j) / nv, y1 = FLOOR_Y + (H * (j + 1)) / nv;
        quad(S, [[TL.lerp(ax, bx, u0), y0, TL.lerp(az, bz, u0)], [TL.lerp(ax, bx, u1), y0, TL.lerp(az, bz, u1)], [TL.lerp(ax, bx, u1), y1, TL.lerp(az, bz, u1)], [TL.lerp(ax, bx, u0), y1, TL.lerp(az, bz, u0)]], [nx, 0, nz], mul(cD, tone));
      }
      // cladding rib at every panel joint
      const x = TL.lerp(ax, bx, i / nu), z = TL.lerp(az, bz, i / nu);
      box(S, nz !== 0 ? 0.07 : 0.05, H, nz !== 0 ? 0.05 : 0.07, x + nx * 0.025, FLOOR_Y + H / 2, z + nz * 0.025, cS, 0, 0, 0, 1, 6, 1);
    }
  };
  wall(X0, Z0, X1, Z0, 0, 1);
  wall(X1, Z1, X0, Z1, 0, -1);
  wall(X0, Z1, X0, Z0, 1, 0);
  wall(X1, Z0, X1, Z1, -1, 0);
  for (let i = 0; i < 9; i++) for (let j = 0; j < 9; j++) { // ceiling
    const xa = TL.lerp(X0, X1, i / 9), xb = TL.lerp(X0, X1, (i + 1) / 9), za = TL.lerp(Z0, Z1, j / 9), zb = TL.lerp(Z0, Z1, (j + 1) / 9);
    quad(S, [[xa, CY, za], [xb, CY, za], [xb, CY, zb], [xa, CY, zb]], [0, -1, 0], mul(cD, 0.8));
  }
  // columns (flange + web) and girts
  const column = (x, z, nx, nz) => {
    const fx = nz !== 0 ? 0.42 : 0.05, fz = nz !== 0 ? 0.05 : 0.42;
    box(S, fx, H, fz, x + nx * 0.42, FLOOR_Y + H / 2, z + nz * 0.42, cS, 0, 0, 0, 1, 8, 1);
    box(S, nz !== 0 ? 0.05 : 0.4, H, nz !== 0 ? 0.4 : 0.05, x + nx * 0.22, FLOOR_Y + H / 2, z + nz * 0.22, mul(cS, 0.8), 0, 0, 0, 1, 8, 1);
    box(S, fx * 1.5, 0.05, fz * 1.5 + 0.2, x + nx * 0.3, FLOOR_Y + 0.025, z + nz * 0.3, cL);
  };
  for (let k = 0; k <= 6; k++) {
    const x = TL.lerp(X0, X1, k / 6);
    if (Math.abs(x) > 6.4) column(x, Z0, 0, 1);
    column(x, Z1, 0, -1);
  }
  for (let k = 1; k < 5; k++) {
    const z = TL.lerp(Z0, Z1, k / 5);
    column(X0, z, 1, 0);
    column(X1, z, -1, 0);
  }
  for (const y of [2.9, 5.9]) {
    box(S, X1 - X0, 0.16, 0.1, 0, y, Z1 - 0.1, cS, 0, 0, 0, 16, 1, 1);
    box(S, 0.1, 0.16, Z1 - Z0, X0 + 0.1, y, (Z0 + Z1) / 2, cS, 0, 0, 0, 1, 1, 16);
    box(S, 0.1, 0.16, Z1 - Z0, X1 - 0.1, y, (Z0 + Z1) / 2, cS, 0, 0, 0, 1, 1, 16);
  }

  // ---- back wall: hangar door with a low teal wash ---------------------------------
  {
    const z = Z0 + 0.2;
    for (let k = 0; k < 30; k++) { // door slats
      const y = FLOOR_Y + 0.13 + k * 0.232;
      box(S, 12, 0.2, 0.07 + 0.03 * (k % 2), 0, y, z, mul(cS, 0.75 + 0.25 * rnd()), 0, 0, 0, 12, 1, 1);
    }
    box(S, 0.5, 7.4, 0.5, -6.3, FLOOR_Y + 3.7, z + 0.1, cL, 0, 0, 0, 1, 8, 1);
    box(S, 0.5, 7.4, 0.5, 6.3, FLOOR_Y + 3.7, z + 0.1, cL, 0, 0, 0, 1, 8, 1);
    box(S, 13.1, 0.6, 0.6, 0, FLOOR_Y + 7.3, z + 0.1, cL, 0, 0, 0, 12, 1, 1);
    // hazard chevrons on the door posts (dull)
    for (let k = 0; k < 5; k++) for (const sx of [-6.3, 6.3]) box(S, 0.51, 0.16, 0.51, sx, FLOOR_Y + 0.3 + k * 0.38, z + 0.1, cY);
    // header: beacon row
    for (let k = -2; k <= 2; k++) {
      box(S, 0.16, 0.1, 0.14, k * 2.4, FLOOR_Y + 6.92, z + 0.42, cD);
      led(k * 2.4, FLOOR_Y + 6.86, z + 0.46, AMBER, 7, 0.05, k === 0 ? 1.6 : 0);
      lamp(k * 2.4, FLOOR_Y + 6.8, z + 0.6, AMBER, 0.5, 0, 4);
    }
    // floor cove: teal wash up the slats
    box(S, 11.4, 0.1, 0.16, 0, FLOOR_Y + 0.05, z + 0.5, cD);
    box(E, 11.2, 0.025, 0.03, 0, FLOOR_Y + 0.105, z + 0.46, mul(TEAL, 1.6));
    for (let k = -4; k <= 4; k++) lamp(k * 1.3, FLOOR_Y + 0.14, z + 0.5, TEAL, 0.55, k === -3 || k === 3 ? 1.0 : 0, 6);
    glow(-3.9, FLOOR_Y + 0.2, z + 0.6, mul(TEAL, 0.05), 2.4);
    glow(3.9, FLOOR_Y + 0.2, z + 0.6, mul(TEAL, 0.05), 2.4);
    glow(0, FLOOR_Y + 0.2, z + 0.6, mul(TEAL, 0.05), 2.6);
  }

  // ---- roof: box trusses across the bay, longitudinal beams, a duct ---------------
  const TRUSS_Y = 7.0, TRUSS_D = 1.15;
  const trussX = (z, xa, xb) => {
    const len = xb - xa, n = Math.round(len / 1.3), bay = len / n;
    for (const dz of [-0.28, 0.28]) {
      box(S, len, 0.13, 0.13, (xa + xb) / 2, TRUSS_Y, z + dz, cS, 0, 0, 0, 10, 1, 1);
      box(S, len, 0.13, 0.13, (xa + xb) / 2, TRUSS_Y + TRUSS_D, z + dz, cS, 0, 0, 0, 10, 1, 1);
      for (let i = 0; i <= n; i++) {
        const x = xa + i * bay;
        box(S, 0.07, TRUSS_D, 0.07, x, TRUSS_Y + TRUSS_D / 2, z + dz, cS);
        if (i < n) {
          const up = i % 2 === 0;
          strut(S, x, TRUSS_Y + (up ? 0 : TRUSS_D), z + dz, x + bay, TRUSS_Y + (up ? TRUSS_D : 0), z + dz, 0.06, 0.06, cS);
        }
      }
    }
    for (let i = 0; i <= n; i += 2) {
      box(S, 0.06, 0.06, 0.56, xa + i * bay, TRUSS_Y, z, cS);
      box(S, 0.06, 0.06, 0.56, xa + i * bay, TRUSS_Y + TRUSS_D, z, cS);
    }
  };
  const TRUSS_Z = [-9.6, -4.6, 0.4, 5.4, 10.4];
  for (const z of TRUSS_Z) trussX(z, X0, X1);
  for (const x of [-6.5, 6.5]) box(S, 0.3, 0.45, Z1 - Z0, x, TRUSS_Y + TRUSS_D + 0.3, (Z0 + Z1) / 2, cS, 0, 0, 0, 1, 1, 14);
  for (const x of [-10.5, -2.2, 2.2, 10.5]) box(S, 0.16, 0.24, Z1 - Z0, x, TRUSS_Y + TRUSS_D + 0.2, (Z0 + Z1) / 2, mul(cS, 0.8), 0, 0, 0, 1, 1, 10);
  // ventilation duct with flanges, hung under the trusses on the suit's right
  tubeAB(S, -9.3, 6.25, Z0 + 0.3, -9.3, 6.25, Z1 - 0.3, 0.42, 0.42, mul(cL, 0.8), 18);
  for (let z = Z0 + 1; z < Z1; z += 2.4) tubeAB(S, -9.3, 6.25, z, -9.3, 6.25, z + 0.07, 0.47, 0.47, cL, 18);
  // cable tray under the trusses on the other side
  box(S, 0.5, 0.06, Z1 - Z0 - 1, 9.0, 6.6, (Z0 + Z1) / 2, cS, 0, 0, 0, 1, 1, 12);
  for (let z = Z0 + 1.5; z < Z1; z += 2.5) { box(S, 0.03, TRUSS_Y - 6.6, 0.03, 8.78, (TRUSS_Y + 6.6) / 2, z, cS); box(S, 0.03, TRUSS_Y - 6.6, 0.03, 9.22, (TRUSS_Y + 6.6) / 2, z, cS); }

  // ---- overhead light rig ---------------------------------------------------------
  // long LED tubes down both sides (perspective lines), dimmer outer pair
  const tubeRun = (x, y, za, zb, col, k) => {
    box(S, 0.14, 0.05, zb - za, x, y + 0.06, (za + zb) / 2, cD);
    box(E, 0.045, 0.045, zb - za - 0.2, x, y, (za + zb) / 2, mul(col, k));
    for (let z = za + 0.6; z < zb; z += 2.5) box(S, 0.025, TRUSS_Y - y - 0.08, 0.025, x, (TRUSS_Y + y + 0.08) / 2, z, cS);
    for (let z = za + 1; z < zb; z += 2.2) lamp(x, y - 0.1, z, col, k * 0.22, 0, 7);
  };
  for (const sx of [-1, 1]) {
    tubeRun(sx * 3.1, 5.1, -7.2, 8.2, COOL, 3.4);
    tubeRun(sx * 5.4, 4.2, -7.2, 8.2, COOL, 1.7);
  }
  // slatted top panel above the turntable in a hung frame
  for (let i = 0; i < 3; i++) {
    const z = -0.9 + i * 1.05;
    box(S, 3.1, 0.12, 0.5, FIX.top.pos[0], 6.5, z, cD);
    box(E, 3.0, 0.02, 0.42, FIX.top.pos[0], 6.43, z, mul(COOL, 2.6));
  }
  for (const sx of [-1.7, 1.7]) box(S, 0.08, 0.08, 3.0, FIX.top.pos[0] + sx, 6.6, 0.15, cS);
  for (const sx of [-1.7, 1.7]) for (const z of [-1.2, 1.5]) box(S, 0.03, TRUSS_Y - 6.6, 0.03, FIX.top.pos[0] + sx, (TRUSS_Y + 6.6) / 2, z, cS);

  /** Hanging spot fixture: barrel + barn doors + yoke + drop pole, lens emitter facing `aim`. */
  const spotFixture = (pos, col, r, lensK, glowK, poleTopZ) => {
    const p = new THREE.Vector3().fromArray(pos), d = new THREE.Vector3().fromArray(AIM).sub(p).normalize();
    const back = p.clone().addScaledVector(d, -0.46), front = p.clone();
    tubeAB(S, back.x, back.y, back.z, front.x, front.y, front.z, r * 0.92, r, cD, 16);
    tubeAB(S, back.x - d.x * 0.1, back.y - d.y * 0.1, back.z - d.z * 0.1, back.x, back.y, back.z, r * 0.6, r * 0.92, cS, 12);
    const lens = p.clone().addScaledVector(d, 0.004);
    tubeAB(E, lens.x, lens.y, lens.z, lens.x + d.x * 0.002, lens.y + d.y * 0.002, lens.z + d.z * 0.002, r * 0.86, r * 0.86, mul(col, lensK), 20);
    // barn doors
    const side = new THREE.Vector3().crossVectors(d, UP).normalize(), upv = new THREE.Vector3().crossVectors(side, d).normalize();
    for (const [ax, sgn] of [[side, 1], [side, -1], [upv, 1], [upv, -1]]) {
      const a = p.clone().addScaledVector(ax, sgn * r), bb = a.clone().addScaledVector(d, 0.2).addScaledVector(ax, sgn * 0.09);
      const other = ax === side ? upv : side;
      _m.makeBasis(other, new THREE.Vector3().subVectors(bb, a).normalize(), new THREE.Vector3().crossVectors(other, new THREE.Vector3().subVectors(bb, a).normalize()));
      _m.scale(_s.set(r * 1.7, 0.22, 0.008)).setPosition(a.add(bb).multiplyScalar(0.5));
      addGeo(S, UNIT_BOX, _m, cD);
    }
    // yoke and drop pole to the truss
    const mid = p.clone().addScaledVector(d, -0.22);
    strut(S, mid.x - side.x * (r + 0.04), mid.y, mid.z - side.z * (r + 0.04), mid.x - side.x * (r + 0.04), mid.y + r + 0.14, mid.z - side.z * (r + 0.04), 0.03, 0.05, cS);
    strut(S, mid.x + side.x * (r + 0.04), mid.y, mid.z + side.z * (r + 0.04), mid.x + side.x * (r + 0.04), mid.y + r + 0.14, mid.z + side.z * (r + 0.04), 0.03, 0.05, cS);
    strut(S, mid.x - side.x * (r + 0.05), mid.y + r + 0.14, mid.z - side.z * (r + 0.05), mid.x + side.x * (r + 0.05), mid.y + r + 0.14, mid.z + side.z * (r + 0.05), 0.05, 0.05, cS);
    strut(S, mid.x, mid.y + r + 0.14, mid.z, mid.x, TRUSS_Y - 0.4, poleTopZ, 0.06, 0.06, cS);
    strut(S, mid.x, TRUSS_Y - 0.4, poleTopZ, mid.x, TRUSS_Y, poleTopZ, 0.1, 0.1, cL);
    // directional flare: bright when the lens faces the camera
    glow(lens.x + d.x * 0.03, lens.y + d.y * 0.03, lens.z + d.z * 0.03, mul(col, glowK), r * 5.5, 5, 1, 4, d);
  };
  spotFixture(FIX.rimA.pos, FIX.rimA.col, 0.17, 26, 5, -4.6);
  spotFixture(FIX.rimB.pos, FIX.rimB.col, 0.17, 24, 4.5, -4.6);
  spotFixture(FIX.key.pos, FIX.key.col, 0.26, 7, 1.6, 5.4);
  // cool fill strip, hung high on the suit's right-front
  box(S, 0.3, 1.9, 0.12, -4.7, 4.3, 3.1, cD, 0, 2.15, 0); box(E, 0.22, 1.8, 0.02, -4.65, 4.3, 3.065, mul(COOL, 1.6), 0, 2.15, 0);
  box(S, 0.03, TRUSS_Y - 5.25, 0.03, -4.7, (TRUSS_Y + 5.25) / 2, 3.1, cS);

  // ---- maintenance cradle behind the suit -------------------------------------------
  {
    const z = -8.5;
    for (const sx of [-1, 1]) {
      const x = sx * 2.75;
      box(S, 1.1, 0.1, 1.1, x, FLOOR_Y + 0.05, z, cL);
      box(S, 0.44, 4.7, 0.44, x, FLOOR_Y + 2.35, z, cS, 0, 0, 0, 1, 8, 1);
      box(S, 0.46, 0.25, 0.46, x, FLOOR_Y + 1.0, z, cY);
      box(S, 0.5, 0.16, 0.5, x, FLOOR_Y + 4.78, z, cL);
      // articulated service arm folded up and forward, tool head parked above head height
      const sh = [x - sx * 0.05, 4.05, z + 0.34], el = [x - sx * 0.45, 5.1, z + 2.2], wr = [x - sx * 1.0, 3.75, z + 3.5];
      tubeAB(S, sh[0] - 0.2, sh[1], sh[2], sh[0] + 0.2, sh[1], sh[2], 0.19, 0.19, cL, 14);
      strut(S, sh[0], sh[1], sh[2], el[0], el[1], el[2], 0.2, 0.26, cS);
      strut(S, sh[0] + sx * 0.14, sh[1] + 0.1, sh[2], el[0] + sx * 0.14, el[1] - 0.5, el[2] - 0.9, 0.05, 0.05, cL); // actuator
      tubeAB(S, el[0] - 0.17, el[1], el[2], el[0] + 0.17, el[1], el[2], 0.16, 0.16, cL, 14);
      strut(S, el[0], el[1], el[2], wr[0], wr[1], wr[2], 0.15, 0.19, cS);
      tubeAB(S, wr[0] - 0.12, wr[1], wr[2], wr[0] + 0.12, wr[1], wr[2], 0.11, 0.11, cY, 12);
      strut(S, wr[0], wr[1], wr[2], wr[0] - sx * 0.12, wr[1] - 0.38, wr[2] + 0.36, 0.1, 0.12, cS);
      for (const g of [-0.05, 0.05]) strut(S, wr[0] - sx * 0.12 + g, wr[1] - 0.38, wr[2] + 0.36, wr[0] - sx * 0.2 + g * 1.6, wr[1] - 0.62, wr[2] + 0.56, 0.02, 0.035, cL);
      led(wr[0], wr[1] + 0.02, wr[2] + 0.13, TEAL, 5, 0.03);
      led(el[0], el[1] + 0.18, el[2], AMBER, 5, 0.03, 1.1 + sx * 0.2);
      cable(R, [[sh[0], sh[1] + 0.2, sh[2]], [(sh[0] + el[0]) / 2 + sx * 0.2, el[1] - 0.2, (sh[2] + el[2]) / 2], [el[0] + sx * 0.15, el[1] - 0.1, el[2]], [(el[0] + wr[0]) / 2 + sx * 0.1, wr[1] + 0.35, (el[2] + wr[2]) / 2], [wr[0], wr[1] + 0.12, wr[2]]], 0.025, cR);
      // tower status strip
      box(E, 0.03, 1.1, 0.012, x - sx * 0.1, 2.3, z + 0.226, mul(TEAL, 1.5));
      lamp(x - sx * 0.1, 2.3, z + 0.5, TEAL, 0.35, 0, 4);
    }
    box(S, 5.9, 0.3, 0.34, 0, 4.72, z, cS, 0, 0, 0, 8, 1, 1);
    for (let k = -4; k <= 4; k++) strut(S, k * 0.62, 4.6, z, (k + (k % 2 === 0 ? 1 : -1) * 0.5) * 0.62, 4.2, z, 0.04, 0.04, cS);
    box(S, 5.0, 0.07, 0.07, 0, 4.18, z, cS, 0, 0, 0, 6, 1, 1);
    // console desk between the towers: monitors glow teal / amber
    box(S, 2.6, 0.95, 0.7, 0, FLOOR_Y + 0.475, z - 0.3, cS, 0, 0, 0, 4, 2, 1);
    for (let k = -1; k <= 1; k++) {
      box(S, 0.62, 0.42, 0.04, k * 0.78, FLOOR_Y + 1.28, z - 0.32, cD, -0.12, 0, 0);
      const sc = k === 0 ? TEAL : [0.35, 0.6, 1.0];
      box(E, 0.56, 0.36, 0.004, k * 0.78, FLOOR_Y + 1.28, z - 0.297, mul(sc, 0.035), -0.12, 0, 0);
      box(E, 0.52, 0.03, 0.004, k * 0.78, FLOOR_Y + 1.425, z - 0.312, mul(sc, 0.5), -0.12, 0, 0);
      for (let j = 0; j < 6; j++) { const w = 0.1 + rnd() * 0.36; box(E, w, 0.014, 0.004, k * 0.78 - 0.26 + w / 2, FLOOR_Y + 1.37 - j * 0.042, z - 0.305 + j * 0.005, mul(sc, 0.25 + rnd() * 0.5), -0.12, 0, 0); }
      box(E, 0.2, 0.09, 0.004, k * 0.78 + 0.15, FLOOR_Y + 1.17, z - 0.281, mul(k === 0 ? [1, 0.5, 0.1] : sc, 0.4), -0.12, 0, 0);
      lamp(k * 0.78, FLOOR_Y + 1.25, z + 0.1, k === 0 ? TEAL : [0.35, 0.6, 1.0], 0.22, 0, 3);
    }
    for (let k = 0; k < 9; k++) led(-1.15 + k * 0.29, FLOOR_Y + 0.99, z + 0.06, k % 3 === 0 ? AMBER : TEAL, 3.5, 0.016, k === 4 ? 0.7 : 0);
  }

  // ---- racks, crates, cylinders around the perimeter (all beyond the camera volume) ---
  const rack = (x, z, ry, w, h, seed) => {
    const r2 = prng(seed), c = Math.cos(ry), s = Math.sin(ry);
    const T = (lx, lz) => [x + lx * c + lz * s, z - lx * s + lz * c];
    for (const lx of [-w / 2, w / 2]) for (const lz of [-0.3, 0.3]) { const [px, pz] = T(lx, lz); box(S, 0.06, h, 0.06, px, FLOOR_Y + h / 2, pz, cL, 0, ry, 0, 1, 3, 1); }
    const shelves = Math.floor(h / 0.62);
    for (let k = 0; k < shelves; k++) {
      const y = FLOOR_Y + 0.14 + k * 0.62;
      box(S, w, 0.04, 0.64, x, y, z, cS, 0, ry, 0, 2, 1, 1);
      let lx = -w / 2 + 0.08;
      while (lx < w / 2 - 0.3) {
        const bw = 0.22 + r2() * 0.5, bh = 0.16 + r2() * 0.38, bd = 0.3 + r2() * 0.25;
        if (r2() < 0.8 && lx + bw < w / 2) {
          const [px, pz] = T(lx + bw / 2, (r2() - 0.5) * 0.1);
          box(S, bw, bh, bd, px, y + 0.02 + bh / 2, pz, mul(r2() < 0.15 ? cY : r2() < 0.5 ? cL : cS, 0.6 + 0.5 * r2()), 0, ry, 0);
          if (r2() < 0.34) { const [qx, qz] = T(lx + bw * 0.3, bd / 2 + 0.012); led(qx, y + 0.02 + bh * 0.7, qz, r2() < 0.5 ? TEAL : r2() < 0.7 ? AMBER : RED, 4.5, 0.018, r2() < 0.25 ? 0.6 + r2() * 1.5 : 0); }
        }
        lx += bw + 0.05;
      }
    }
  };
  rack(11.7, -7.5, -Math.PI / 2, 3.2, 3.9, 11);
  rack(11.7, -3.4, -Math.PI / 2, 3.2, 3.9, 12);
  rack(11.7, 4.8, -Math.PI / 2, 3.2, 3.3, 13);
  rack(-11.7, 2.5, Math.PI / 2, 3.2, 3.9, 14);
  rack(-11.7, 6.6, Math.PI / 2, 3.2, 3.3, 15);
  rack(-7.6, 12.6, Math.PI, 3.2, 2.7, 16);
  rack(8.2, 12.6, Math.PI, 3.2, 3.3, 17);
  const crate = (x, z, w, h, d, ry, col) => box(S, w, h, d, x, FLOOR_Y + h / 2, z, col, 0, ry, 0, 1, 2, 1);
  crate(8.6, -8.9, 1.3, 1.0, 1.0, 0.2, cS); crate(8.5, -8.85, 0.9, 0.7, 0.8, 0.5, cL); crate(9.9, -8.2, 1.1, 1.5, 1.1, -0.1, mul(cS, 0.8));
  box(S, 0.9, 0.7, 0.8, 8.5, FLOOR_Y + 1.35, -8.85, mul(cL, 0.7), 0, 0.42, 0);
  crate(-9.6, -9.2, 1.4, 1.2, 1.1, 0.1, cS); crate(-10.2, 9.5, 1.6, 1.1, 1.2, 0.3, cS); crate(10.0, 9.2, 1.2, 1.6, 1.2, -0.25, cS);
  crate(-5.2, -9.9, 1.0, 0.8, 0.8, 0.15, mul(cY, 0.7));
  for (let k = 0; k < 5; k++) { // gas cylinders in a cage
    const x = 6.6 + k * 0.3, z = -10.4;
    cyl(S, 0.115, 1.45, x, FLOOR_Y + 0.725, z, k % 2 ? mul(cL, 0.9) : lin(0x2b3a44), 10);
    cyl(S, 0.04, 0.12, x, FLOOR_Y + 1.5, z, cL, 8);
  }
  box(S, 1.7, 0.04, 0.04, 7.2, FLOOR_Y + 1.0, -10.2, cY);
  // rolling tool cabinet + stool near the suit's right wall
  box(S, 1.2, 1.0, 0.6, -10.6, FLOOR_Y + 0.6, -4.2, lin(0x4a1512), 0, 0.5, 0, 2, 2, 1);
  for (let k = 0; k < 4; k++) box(S, 1.1, 0.02, 0.02, -10.35 + 0.0, FLOOR_Y + 0.3 + k * 0.22, -4.06 + 0.0, cL, 0, 0.5, 0);

  // ---- welding bay (suit's back-right): bench, part, robot arm; arc + sparks animate ----
  {
    const [wx, wy, wz] = WELD.pos;
    box(S, 2.2, 0.08, 1.1, wx - 0.3, FLOOR_Y + 0.86, wz - 0.3, cL, 0, 0.4, 0, 3, 1, 2);
    for (const [lx, lz] of [[-1.0, -0.4], [1.0, -0.4], [-1.0, 0.4], [1.0, 0.4]]) box(S, 0.07, 0.84, 0.07, wx - 0.3 + lx * 0.92 + lz * 0.39, FLOOR_Y + 0.42, wz - 0.3 - lx * 0.39 + lz * 0.92, cS);
    box(S, 0.8, 0.34, 0.5, wx - 0.2, FLOOR_Y + 1.07, wz - 0.25, cS, 0, 0.6, 0, 2, 1, 1); // workpiece
    box(S, 0.5, 0.5, 0.5, wx - 1.5, FLOOR_Y + 0.25, wz - 1.2, cY); // robot base
    tubeAB(S, wx - 1.5, FLOOR_Y + 0.5, wz - 1.2, wx - 1.5, FLOOR_Y + 1.3, wz - 1.2, 0.16, 0.13, cL, 12);
    strut(S, wx - 1.5, FLOOR_Y + 1.3, wz - 1.2, wx - 0.9, 2.1, wz - 0.7, 0.14, 0.18, cY);
    strut(S, wx - 0.9, 2.1, wz - 0.7, wx - 0.12, wy + 0.3, wz - 0.1, 0.1, 0.12, cS);
    strut(S, wx - 0.12, wy + 0.3, wz - 0.1, wx, wy + 0.02, wz, 0.03, 0.03, cL);
    // privacy screens either side of the bay
    box(S, 0.04, 2.0, 1.8, wx - 2.4, FLOOR_Y + 1.0, wz - 0.4, mul(cS, 0.7), 0, 0.25, 0, 1, 3, 3);
    glow(wx, wy, wz, [34, 44, 70], 0.16, 3);
    glow(wx, wy, wz, [1.6, 2.2, 3.8], 1.5, 3);
  }

  // ---- sodium work lamp on a stand (suit's back-left): the one warm pool ---------------
  {
    const x = 7.9, z = -6.1;
    box(S, 0.5, 0.04, 0.5, x, FLOOR_Y + 0.02, z, cS);
    box(S, 0.04, 1.3, 0.04, x, FLOOR_Y + 0.65, z, cL);
    box(S, 0.34, 0.24, 0.12, x, FLOOR_Y + 1.36, z, cD, 0.25, -0.9, 0);
    box(E, 0.28, 0.18, 0.01, x - 0.05, FLOOR_Y + 1.35, z + 0.045, mul(SODIUM, 12), 0.25, -0.9, 0);
    glow(x - 0.07, FLOOR_Y + 1.34, z + 0.06, mul(SODIUM, 5), 0.3);
    glow(x - 0.07, FLOOR_Y + 1.34, z + 0.06, mul(SODIUM, 0.14), 1.9);
    lamp(x - 0.25, FLOOR_Y + 1.3, z + 0.25, SODIUM, 5.5, 1.1, 9);
  }

  // ---- teal service strips on columns --------------------------------------------------
  const strip = (x, y, z, nx, nz, len, k, floor) => {
    box(S, nz !== 0 ? 0.12 : 0.06, len + 0.1, nz !== 0 ? 0.06 : 0.12, x, y, z, cD);
    box(E, nz !== 0 ? 0.05 : 0.012, len, nz !== 0 ? 0.012 : 0.05, x + nx * 0.034, y, z + nz * 0.034, mul(TEAL, k));
    glow(x + nx * 0.1, y, z + nz * 0.1, mul(TEAL, k * 0.04), len * 0.6);
    for (let j = -1; j <= 1; j++) lamp(x + nx * 0.3, y + j * len * 0.33, z + nz * 0.3, TEAL, k * 0.6, j === -1 ? floor : 0, 7);
  };
  strip(X0 + 0.7, 1.4, TL.lerp(Z0, Z1, 1 / 5), 1, 0, 2.4, 1.5, 2);
  strip(X0 + 0.7, 1.4, TL.lerp(Z0, Z1, 3 / 5), 1, 0, 2.4, 1.2, 1.5);
  strip(X1 - 0.7, 1.4, TL.lerp(Z0, Z1, 2 / 5), -1, 0, 2.4, 1.4, 2);
  strip(X1 - 0.7, 1.4, TL.lerp(Z0, Z1, 4 / 5), -1, 0, 2.4, 1.1, 1);
  strip(-8.667, 1.4, Z0 + 0.72, 0, 1, 2.4, 1.3, 1.5);
  strip(8.667, 1.4, Z1 - 0.72, 0, -1, 2.4, 1.1, 1);

  // ---- mezzanine walkway along the suit's left wall, bulkhead lamps underneath ---------
  {
    const xa = X1 - 1.9, y = 3.5;
    box(S, 1.9, 0.1, 17, X1 - 0.95, y, 0.5, cS, 0, 0, 0, 1, 1, 12);
    box(S, 0.12, 0.24, 17, xa + 0.06, y - 0.12, 0.5, cL, 0, 0, 0, 1, 1, 12);
    for (let z = -8; z <= 9; z += 1.7) {
      box(S, 0.04, 1.05, 0.04, xa + 0.04, y + 0.57, z, cL);
      if (Math.abs(z - 0.5) > 0.1 && Math.round((z + 8) / 1.7) % 3 === 0) box(S, 0.12, y - FLOOR_Y, 0.12, xa + 0.1, FLOOR_Y + (y - FLOOR_Y) / 2, z, cS, 0, 0, 0, 1, 4, 1);
    }
    box(S, 0.04, 0.04, 17, xa + 0.04, y + 1.1, 0.5, cY, 0, 0, 0, 1, 1, 8);
    box(S, 0.03, 0.03, 17, xa + 0.04, y + 0.6, 0.5, cL, 0, 0, 0, 1, 1, 8);
    for (const z of [-6.2, -0.9, 4.4]) {
      box(S, 0.3, 0.07, 0.16, xa + 0.6, y - 0.09, z, cD);
      box(E, 0.24, 0.012, 0.1, xa + 0.6, y - 0.13, z, mul(WARM, 4.5));
      glow(xa + 0.6, y - 0.16, z, mul(WARM, 0.5), 0.34);
      lamp(xa + 0.6, y - 0.3, z, WARM, 2.2, z === -0.9 ? 1.6 : 1.0, 7);
    }
  }

  // ---- control room across the front wall: a row of dim warm windows -------------------
  {
    const z = Z1 - 0.25, y = 4.35;
    box(S, 15.6, 0.3, 0.5, 0, y - 0.82, z, cL, 0, 0, 0, 10, 1, 1);
    box(S, 15.6, 0.3, 0.5, 0, y + 0.82, z, cL, 0, 0, 0, 10, 1, 1);
    box(S, 15.6, 1.4, 0.1, 0, y, z + 0.18, mul(cD, 0.5));
    const AMB = [1.0, 0.74, 0.44];
    for (let k = -5; k <= 5; k++) {
      const x = k * 1.4, lvl = [0.3, 0.05, 0.22, 0.34, 0.0, 0.16, 0.3, 0.26, 0.04, 0.2, 0.3][k + 5] * 0.36;
      box(S, 0.12, 1.4, 0.16, x - 0.7, y, z - 0.04, cS);
      if (k === 5) box(S, 0.12, 1.4, 0.16, x + 0.7, y, z - 0.04, cS);
      if (lvl <= 0) continue;
      // pane: brighter low down (desk lamps), darker toward the head rail
      for (let j = 0; j < 4; j++) box(E, 1.28, 0.33, 0.01, x, y - 0.5 + j * 0.335, z + 0.1, mul(AMB, lvl * [1.0, 0.72, 0.5, 0.36][j]));
      if (k % 2 === 0) { box(S, 0.5 + rnd() * 0.4, 0.28 + rnd() * 0.3, 0.04, x + (rnd() - 0.5) * 0.4, y - 0.52, z + 0.06, cD); led(x + 0.3, y - 0.3, z - 0.02, rnd() < 0.5 ? TEAL : AMBER, 2.5, 0.016); }
      lamp(x, y - 0.2, z - 0.7, AMB, lvl * 3.2, k === 1 ? 1.4 : 0, 7);
    }
    // personnel door + lamp under it
    box(S, 1.0, 2.1, 0.08, -10.4, FLOOR_Y + 1.05, z + 0.1, cS);
    box(E, 0.3, 0.07, 0.03, -10.4, FLOOR_Y + 2.32, z + 0.05, mul([0.3, 1.0, 0.5], 2.2));
    lamp(-10.4, FLOOR_Y + 2.2, z - 0.4, [0.3, 1.0, 0.5], 0.5, 0, 4);
  }

  // ---- scattered tiny practicals high on the walls / trusses (bokeh) --------------------
  for (let i = 0; i < 46; i++) {
    const a = rnd() * TAU, onTruss = rnd() < 0.35;
    let x, y, z;
    if (onTruss) { z = TRUSS_Z[Math.floor(rnd() * TRUSS_Z.length)] + (rnd() < 0.5 ? -0.36 : 0.36); x = TL.lerp(X0 + 1, X1 - 1, rnd()); y = TRUSS_Y - 0.1 + (rnd() < 0.5 ? 0 : TRUSS_D); }
    else {
      const wsel = Math.floor(rnd() * 4);
      y = 2.9 + rnd() * 4.6;
      if (wsel === 0) { x = TL.lerp(X0 + 1, X1 - 1, rnd()); z = Z0 + 0.14; if (Math.abs(x) < 6.2) y = 7.2 + rnd() * 1.4; }
      else if (wsel === 1) { x = TL.lerp(X0 + 1, X1 - 1, rnd()); z = Z1 - 0.14; if (y > 3 && y < 5.6) y = 5.9 + rnd(); }
      else if (wsel === 2) { x = X0 + 0.14; z = TL.lerp(Z0 + 1, Z1 - 1, rnd()); }
      else { x = X1 - 0.14; z = TL.lerp(Z0 + 1, Z1 - 1, rnd()); }
    }
    const pick = rnd();
    const col = pick < 0.3 ? AMBER : pick < 0.62 ? TEAL : pick < 0.72 ? RED : COOL;
    led(x, y, z, col, 5 + rnd() * 7, 0.02 + rnd() * 0.02, rnd() < 0.16 ? 0.8 + rnd() * 1.8 : 0);
    void a;
  }

  // ---- cable trunking from the plinth off into the dark --------------------------------
  const trunk = (pts, wCol) => {
    // ramp-style cable protectors along the polyline, then loose hoses beside it
    for (let k = 0; k < pts.length - 1; k++) {
      const [ax, az] = pts[k], [bx, bz] = pts[k + 1];
      const len = Math.hypot(bx - ax, bz - az), n = Math.max(1, Math.round(len / 0.92)), ry = Math.atan2(bx - ax, bz - az);
      for (let i = 0; i < n; i++) {
        const u = (i + 0.5) / n, x = TL.lerp(ax, bx, u), z = TL.lerp(az, bz, u), seg = len / n - 0.02;
        box(R, 0.46, 0.035, seg, x, FLOOR_Y + 0.0175, z, cR, 0, ry, 0);
        box(R, 0.2, 0.058, seg, x, FLOOR_Y + 0.029, z, mul(wCol, 0.8), 0, ry, 0);
        for (const sx of [-0.165, 0.165]) box(R, 0.1, 0.04, seg, x + Math.cos(ry) * sx, FLOOR_Y + 0.03, z - Math.sin(ry) * sx, cR, 0, ry, sx > 0 ? -0.28 : 0.28);
      }
    }
    for (const [off, r] of [[0.33, 0.022], [0.39, 0.014], [-0.34, 0.018]]) {
      const path = [];
      for (let k = 0; k < pts.length; k++) {
        const prev = pts[Math.max(0, k - 1)], next = pts[Math.min(pts.length - 1, k + 1)];
        const dx = next[0] - prev[0], dz = next[1] - prev[1], l = Math.hypot(dx, dz) || 1;
        const wob = k === 0 ? 0 : (rnd() - 0.5) * 0.12;
        path.push([pts[k][0] + (dz / l) * (off + wob), FLOOR_Y + r, pts[k][1] - (dx / l) * (off + wob)]);
        if (k < pts.length - 1) {
          const mx = (pts[k][0] + pts[k + 1][0]) / 2, mz = (pts[k][1] + pts[k + 1][1]) / 2, w2 = (rnd() - 0.5) * 0.16;
          path.push([mx + (dz / l) * (off + w2), FLOOR_Y + r, mz - (dx / l) * (off + w2)]);
        }
      }
      cable(R, path, r, cR);
    }
  };
  const trunkAt = (azDeg, bends, wCol = cY) => {
    const a = azDeg * DEG, pts = [pol(1.9, a)];
    for (const [r, da] of bends) pts.push(pol(r, (azDeg + da) * DEG));
    trunk(pts, wCol);
    // junction box on the skirt where the run plugs in
    const [jx, jz] = pol(1.9, a);
    box(S, 0.5, 0.2, 0.22, jx, FLOOR_Y + 0.1, jz, cS, 0, a, 0);
    led(jx + Math.sin(a) * 0.12 + Math.cos(a) * 0.17, FLOOR_Y + 0.15, jz + Math.cos(a) * 0.12 - Math.sin(a) * 0.17, TEAL, 4, 0.014);
    led(jx + Math.sin(a) * 0.12 - Math.cos(a) * 0.17, FLOOR_Y + 0.15, jz + Math.cos(a) * 0.12 + Math.sin(a) * 0.17, AMBER, 4, 0.014, 0.9);
  };
  trunkAt(163, [[4.6, -3], [8.4, -1], [10.8, 4]]);
  trunkAt(222, [[4.4, 4], [8.0, 7], [11.6, 5]]);
  trunkAt(98, [[4.5, -4], [8.2, -6], [11.2, -5]], lin(0x33383f));
  trunkAt(305, [[4.2, 5], [7.4, 9], [12.6, 16]], lin(0x33383f));

  // ---- bake the practicals into the structure -------------------------------------
  S.bake = new Array(S.p.length).fill(0);
  for (let i = 0; i < S.count; i++) {
    const x = S.p[i * 3], y = S.p[i * 3 + 1], z = S.p[i * 3 + 2], nx = S.n[i * 3], ny = S.n[i * 3 + 1], nz = S.n[i * 3 + 2];
    let r = 0, g = 0, bb = 0;
    for (const L of lamps) {
      const dx = L.x - x, dy = L.y - y, dz = L.z - z, d2 = dx * dx + dy * dy + dz * dz;
      if (d2 > L.range * L.range) continue;
      const d = Math.sqrt(d2) || 1e-3;
      const nl = Math.max(0, (nx * dx + ny * dy + nz * dz) / d) * 0.85 + 0.15; // a little wrap: bounce
      const w = (nl / (d2 + 0.25)) * (1 - d2 / (L.range * L.range));
      r += L.r * w; g += L.g * w; bb += L.b * w;
    }
    S.bake[i * 3] = r; S.bake[i * 3 + 1] = g; S.bake[i * 3 + 2] = bb;
  }

  const structMat = makePaintMaterial(U.bake);
  const rubberMat = new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.0, roughness: 0.62 });
  const emisMat = new THREE.MeshBasicMaterial({ vertexColors: true });
  const mS = finish(S, structMat, 'structure'), mR = finish(R, rubberMat, 'cables'), mE = finish(E, emisMat, 'emitters');
  mR.receiveShadow = true;
  backdrop.add(mS, mR, mE);

  // ---- floor ---------------------------------------------------------------------
  const floorMat = makeFloorMaterial(U, tex.noise);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(HALL.x1 - HALL.x0 + 2, HALL.z1 - HALL.z0 + 2), floorMat);
  floor.rotation.x = -Math.PI / 2;
  floor.position.set(0, FLOOR_Y, (HALL.z0 + HALL.z1) / 2);
  floor.receiveShadow = true;
  floor.name = 'floor';
  backdrop.add(floor);

  return { envMats: [structMat, rubberMat, floorMat], floorMat };
}

// =====================================================================================
// glows: instanced camera-facing discs for LEDs, lamp flares, lock flashes, the weld arc
// =====================================================================================
function makeGlowSystem(U, capacity) {
  const pos = new Float32Array(capacity * 3), col = new Float32Array(capacity * 3), parm = new Float32Array(capacity * 4), dir = new Float32Array(capacity * 3);
  let count = 0;
  const geo = new THREE.InstancedBufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute([-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0], 3));
  geo.setIndex([0, 1, 2, 0, 2, 3]);
  const aPos = new THREE.InstancedBufferAttribute(pos, 3), aCol = new THREE.InstancedBufferAttribute(col, 3), aParm = new THREE.InstancedBufferAttribute(parm, 4), aDir = new THREE.InstancedBufferAttribute(dir, 3);
  geo.setAttribute('aPos', aPos); geo.setAttribute('aCol', aCol); geo.setAttribute('aParm', aParm); geo.setAttribute('aDir', aDir);
  geo.instanceCount = 0;
  const mat = new THREE.ShaderMaterial({
    uniforms: { uTime: U.time, uPx: U.px, uWeld: U.weld, uEnergy: U.energy, uRim: U.rim },
    vertexShader: /* glsl */ `
      attribute vec3 aPos; attribute vec3 aCol; attribute vec4 aParm; attribute vec3 aDir;
      uniform float uTime, uPx, uWeld, uEnergy, uRim;
      varying vec2 vUv; varying vec3 vCol;
      void main() {
        float I = 1.0;
        int type = int(aParm.y + 0.5);
        if (type == 1) { float f = fract(uTime / aParm.z + aParm.w); I = smoothstep(0.0, 0.04, f) * (1.0 - smoothstep(0.4, 0.5, f)); }
        else if (type == 2) { float tau = uTime - aParm.z; I = tau < 0.0 ? 0.0 : exp(-tau / aParm.w); }
        else if (type == 3) { I = uWeld; }
        else if (type == 4) { I = uEnergy; }
        else if (type == 5) { I = pow(max(dot(aDir, normalize(cameraPosition - aPos)), 0.0), aParm.w) * mix(1.0, uRim, aParm.z); }
        vec4 mv = viewMatrix * vec4(aPos, 1.0);
        if (type == 5) mv.xyz *= 1.0 - 0.4 / max(length(mv.xyz), 0.8);   // lens glare sits in front of the barn doors
        float rad = aParm.x;
        float px = rad * uPx / max(-mv.z, 0.05);
        float k = max(1.0, 1.5 / px);            // never smaller than ~1.5 px: keep the energy, lose the flicker
        rad *= k; I /= k * k;
        if (I < 0.0004) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
        mv.xy += position.xy * rad;
        gl_Position = projectionMatrix * mv;
        vUv = position.xy; vCol = aCol * I;
      }`,
    fragmentShader: /* glsl */ `
      varying vec2 vUv; varying vec3 vCol;
      void main() {
        float r2 = dot(vUv, vUv);
        if (r2 > 1.0) discard;
        float fall = (1.0 - r2); fall *= fall;
        float g = 0.8 * exp(-r2 * 30.0) + 0.2 / (1.0 + r2 * 60.0) + 0.05 * exp(-r2 * 3.5);
        gl_FragColor = vec4(vCol * g * fall, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.frustumCulled = false;
  mesh.renderOrder = 6;
  mesh.name = 'glows';
  /** type: 0 steady · 1 blink(period, phase) · 2 flash(t, decay) · 3 weld arc · 4 energy · 5 directional(rimGain, power, dir) */
  function glow(x, y, z, c, radius, type = 0, a = 0, b = 0, d = null) {
    if (count >= capacity) return -1;
    const i = count++;
    pos.set([x, y, z], i * 3); col.set(c, i * 3); parm.set([radius, type, a, b], i * 4);
    if (d) dir.set([d.x, d.y, d.z], i * 3);
    geo.instanceCount = count;
    aPos.needsUpdate = aCol.needsUpdate = aParm.needsUpdate = aDir.needsUpdate = true;
    return i;
  }
  return { mesh, glow };
}

// =====================================================================================
// volumetric light shafts
// =====================================================================================
const BEAM_FRAG = /* glsl */ `
  uniform vec3 uApex, uAxis, uColor;
  uniform float uCos, uCosCore, uNear, uFar, uClipY, uSoft, uG, uTime, uGain;
  uniform sampler2D uNoise;
  varying vec3 vW;
  void main() {
    vec3 O = cameraPosition;
    vec3 V = vW - O;
    float tF = length(V);
    V /= tF;
    vec3 co = O - uApex;
    float vd = dot(V, uAxis), cd = dot(co, uAxis), vc = dot(V, co), cc = dot(co, co);
    float c2 = uCos * uCos;
    // ray ∩ solid cone: f(t) = (axial)^2 - cos^2 * |p|^2 >= 0   →   a t^2 + 2 b t + c >= 0
    float a = vd * vd - c2;
    float b = vd * cd - vc * c2;
    float c = cd * cd - cc * c2;
    if (abs(a) < 1e-5) a = a < 0.0 ? -1e-5 : 1e-5;
    float disc = b * b - a * c;
    float tin = 0.0, tout = tF;
    if (a < 0.0) {
      if (disc <= 0.0) discard;
      float s = sqrt(disc);
      tin = max(tin, (-b + s) / a);
      tout = min(tout, (-b - s) / a);
    } else if (disc > 0.0) {
      float s = sqrt(disc);
      if (vd > 0.0) tin = max(tin, (-b + s) / a); else tout = min(tout, (-b - s) / a);
    }
    // axial slab [near, far] (also rejects the mirror nappe) and the floor clip plane
    float ivd = 1.0 / (abs(vd) < 1e-5 ? (vd < 0.0 ? -1e-5 : 1e-5) : vd);
    float t0 = (uNear - cd) * ivd, t1 = (uFar - cd) * ivd;
    tin = max(tin, min(t0, t1));
    tout = min(tout, max(t0, t1));
    float ivy = 1.0 / (abs(V.y) < 1e-5 ? 1e-5 : V.y);
    float ty = (uClipY - O.y) * ivy;
    if (V.y > 0.0) tin = max(tin, ty); else tout = min(tout, ty);
    if (tout <= tin) discard;
    // exact in-scatter of an inverse-square source along the segment (airlight integral)
    float tp = -vc;
    float h = sqrt(max(cc - vc * vc, 0.0) + uSoft * uSoft);
    float I = (atan((tout - tp) / h) - atan((tin - tp) / h)) / h;
    // representative point: where the segment passes closest to the beam axis
    float den = vd * vc - cd;
    float ts = clamp((cd * vc - vd * cc) / (abs(den) < 1e-5 ? 1e-5 : den), tin, tout);
    vec3 P = co + V * ts;
    float lp = length(P);
    float ax = dot(P, uAxis);
    float prof = smoothstep(uCos, uCosCore, ax / max(lp, 1e-4));
    float fade = 1.0 - smoothstep(uFar * 0.55, uFar, ax);
    float cs = dot(P / max(lp, 1e-4), -V);
    float phase = (1.0 - uG * uG) / pow(1.0 + uG * uG - 2.0 * uG * cs, 1.5);
    // smoke density: two taps inside the segment (the closest-approach point would collapse onto
    // the axis when looking along the beam and draw rings)
    vec3 Wa = O + V * mix(tin, tout, 0.3), Wb = O + V * mix(tin, tout, 0.72);
    float n1 = texture2D(uNoise, Wa.xz * 0.13 + vec2(uTime * 0.011, Wa.y * 0.11 - uTime * 0.006)).r;
    float n2 = texture2D(uNoise, vec2(Wb.x + Wb.z * 0.6, Wb.y * 1.2 - Wb.z * 0.4) * 0.27 + vec2(-uTime * 0.017, uTime * 0.009)).g;
    float wisps = 0.5 + 0.55 * n1 + 0.55 * n2 + 0.5 * n1 * n2;
    vec3 col = uColor * (uGain * I * (0.3 + 0.7 * prof) * fade * phase * wisps);
    // tiny ordered noise so the smooth gradient never bands in 8 bits
    col += col * (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * 0.06;
    gl_FragColor = vec4(col, 1.0);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }`;
const WORLD_VERT = /* glsl */ `
  varying vec3 vW;
  void main() {
    vec4 w = modelMatrix * vec4(position, 1.0);
    vW = w.xyz;
    gl_Position = projectionMatrix * viewMatrix * w;
  }`;

/**
 * One light shaft. The mesh is only a convex bound drawn with BACK faces and the depth test on:
 * a pixel gets haze only where the far side of the shaft is visible, so anything standing inside
 * the beam (the suit) stays clean and contrasty and reads as a silhouette against the glow.
 */
function makeBeam(U, noise, { from, half, aperture, far, clipY = 0.02, color, gain, g = 0.4, core = 0.45 }) {
  const lampPos = new THREE.Vector3().fromArray(from);
  const axis = new THREE.Vector3().fromArray(AIM).sub(lampPos).normalize();
  const back = aperture / Math.tan(half); // virtual apex sits behind the lens so the cone starts at the aperture
  const apex = lampPos.clone().addScaledVector(axis, -back);
  const near = back, farAx = back + far;
  // gain is quoted as radiance per metre of path at the aim point; the 1/d² then makes the
  // shaft hotter toward the lamp
  const d0 = apex.distanceTo(new THREE.Vector3().fromArray(AIM));
  gain *= d0 * d0;
  // convex bound: frustum (slightly fat) clipped by the floor plane
  const N = 18, fat = 1.06 / Math.cos(Math.PI / N);
  const side = new THREE.Vector3().crossVectors(axis, Math.abs(axis.y) > 0.9 ? new THREE.Vector3(1, 0, 0) : UP).normalize();
  const upv = new THREE.Vector3().crossVectors(side, axis).normalize();
  const ring = (ax) => {
    const out = [];
    for (let i = 0; i < N; i++) {
      const a = (i / N) * TAU, r = ax * Math.tan(half) * fat;
      out.push(apex.clone().addScaledVector(axis, ax).addScaledVector(side, Math.cos(a) * r).addScaledVector(upv, Math.sin(a) * r));
    }
    return out;
  };
  const rn = ring(near * 0.98), rf = ring(farAx);
  const pts = [];
  const edge = (p, q) => { if ((p.y - clipY) * (q.y - clipY) < 0) pts.push(p.clone().lerp(q, (clipY - p.y) / (q.y - p.y))); };
  for (let i = 0; i < N; i++) {
    const j = (i + 1) % N;
    for (const p of [rn[i], rf[i]]) if (p.y >= clipY) pts.push(p);
    edge(rn[i], rf[i]); edge(rn[i], rn[j]); edge(rf[i], rf[j]);
  }
  const geo = new ConvexGeometry(pts);
  const uniforms = {
    uApex: { value: apex }, uAxis: { value: axis }, uColor: { value: new THREE.Color().setRGB(color[0], color[1], color[2]) },
    uCos: { value: Math.cos(half) }, uCosCore: { value: Math.cos(half * core) }, uNear: { value: near }, uFar: { value: farAx },
    uClipY: { value: clipY }, uSoft: { value: 0.3 }, uG: { value: g }, uTime: U.time, uGain: { value: gain }, uNoise: { value: noise },
  };
  const mat = new THREE.ShaderMaterial({ uniforms, vertexShader: WORLD_VERT, fragmentShader: BEAM_FRAG, side: THREE.BackSide, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.frustumCulled = false;
  mesh.renderOrder = 4;
  mesh.name = 'beam';
  return { mesh, uniforms, apex, axis, half, gain };
}

function buildHaze(env, beams) {
  const { backdrop, U, tex } = env;
  for (const b of beams) backdrop.add(b.mesh);

  // ---- drifting haze shells: two big cylinders around the stage, always behind the action ----
  const shellMat = (gain, k1, k2, dir) => new THREE.ShaderMaterial({
    uniforms: { uNoise: { value: tex.noise }, uTime: U.time, uGain: { value: gain }, uK: { value: new THREE.Vector3(k1, k2, dir) } },
    vertexShader: WORLD_VERT,
    fragmentShader: /* glsl */ `
      uniform sampler2D uNoise; uniform float uTime, uGain; uniform vec3 uK;
      varying vec3 vW;
      void main() {
        float ang = atan(vW.x, vW.z) / 6.2831853 + 0.5;
        float y = vW.y + 0.35;
        float n1 = texture2D(uNoise, vec2(ang * uK.x + uTime * 0.0035 * uK.z, y * 0.085 - uTime * 0.0022)).r;
        float n2 = texture2D(uNoise, vec2(ang * uK.y - uTime * 0.005 * uK.z, y * 0.19 + uTime * 0.003)).g;
        float n = smoothstep(0.2, 0.95, n1 * 0.7 + n2 * 0.55);
        float vert = smoothstep(0.0, 0.5, y) * exp(-y / 2.6) + 0.35 * exp(-y / 0.5);
        // teal toward the door wash, warm near the sodium lamp, cold elsewhere
        vec3 dirv = normalize(vec3(vW.x, 0.0, vW.z));
        float backness = smoothstep(0.2, 1.0, -dirv.z);
        float warm = pow(max(dot(dirv, normalize(vec3(7.9, 0.0, -6.1))), 0.0), 14.0);
        vec3 tint = mix(vec3(0.34, 0.5, 0.72), vec3(0.1, 0.72, 0.66), backness) + vec3(1.3, 0.5, 0.1) * warm;
        vec3 col = tint * (uGain * n * vert);
        col += col * (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * 0.08;
        gl_FragColor = vec4(col, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    side: THREE.BackSide, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
  });
  const shellA = new THREE.Mesh(new THREE.CylinderGeometry(8.4, 8.4, 9, 48, 1, true), shellMat(0.03, 3, 7, 1));
  shellA.position.y = FLOOR_Y + 4.5;
  const shellB = new THREE.Mesh(new THREE.CylinderGeometry(10.6, 10.6, 9, 48, 1, true), shellMat(0.04, 2, 5, -1));
  shellB.position.y = FLOOR_Y + 4.5;
  shellA.renderOrder = 3; shellB.renderOrder = 3;
  shellA.frustumCulled = shellB.frustumCulled = false;
  backdrop.add(shellA, shellB);

  // ---- dust motes: drift on closed-form paths, light up inside the shafts -----------------
  const N = 1100, rnd = prng(0xd057);
  const BOX = [4.6, 2.3, 4.6];
  const p = new Float32Array(N * 3), seed = new Float32Array(N * 4);
  for (let i = 0; i < N; i++) {
    p[i * 3] = (rnd() * 2 - 1) * BOX[0]; p[i * 3 + 1] = (rnd() * 2 - 1) * BOX[1]; p[i * 3 + 2] = (rnd() * 2 - 1) * BOX[2];
    seed[i * 4] = rnd(); seed[i * 4 + 1] = rnd(); seed[i * 4 + 2] = rnd(); seed[i * 4 + 3] = rnd();
  }
  const dg = new THREE.BufferGeometry();
  dg.setAttribute('position', new THREE.BufferAttribute(p, 3));
  dg.setAttribute('aSeed', new THREE.BufferAttribute(seed, 4));
  const NB = beams.length;
  const dustMat = new THREE.ShaderMaterial({
    defines: { NB },
    uniforms: {
      uTime: U.time, uPx: U.px,
      uBA: { value: beams.map((b) => b.apex) }, uBD: { value: beams.map((b) => b.axis) },
      uBC: { value: beams.map((b) => b.uniforms.uColor.value) }, uBP: { value: beams.map((b) => new THREE.Vector2(Math.cos(b.half), b.dust)) },
    },
    vertexShader: /* glsl */ `
      attribute vec4 aSeed;
      uniform float uTime, uPx;
      uniform vec3 uBA[NB], uBD[NB], uBC[NB]; uniform vec2 uBP[NB];
      varying vec3 vCol;
      const vec3 BOX = vec3(${BOX[0].toFixed(1)}, ${BOX[1].toFixed(1)}, ${BOX[2].toFixed(1)});
      void main() {
        vec3 p = position;
        float t = uTime;
        p.x += sin(t * 0.23 * (0.5 + aSeed.x) + aSeed.y * 6.28) * 0.14 + t * 0.021;
        p.y += sin(t * 0.19 * (0.5 + aSeed.z) + aSeed.x * 6.28) * 0.09 + t * 0.008 * (aSeed.w - 0.35);
        p.z += cos(t * 0.21 * (0.5 + aSeed.w) + aSeed.z * 6.28) * 0.14 - t * 0.012;
        p = mod(p + BOX, 2.0 * BOX) - BOX;
        p.y += BOX.y + 0.05;
        vec3 col = vec3(0.01, 0.014, 0.018);
        for (int i = 0; i < NB; i++) {
          vec3 d = p - uBA[i];
          float l = length(d);
          float c = dot(d, uBD[i]) / l;
          col += uBC[i] * (uBP[i].y * smoothstep(uBP[i].x, uBP[i].x + 0.01, c) / (l * l + 1.0));
        }
        float tw = 0.3 + 0.7 * pow(0.5 + 0.5 * sin(t * (1.2 + 3.0 * aSeed.y) + aSeed.w * 40.0), 3.0);
        vec4 mv = viewMatrix * vec4(p, 1.0);
        float dist = -mv.z;
        float px = (0.0022 + 0.0042 * aSeed.z * aSeed.z) * uPx / max(dist, 0.05);
        float k = max(1.0, 0.85 / px);
        px = min(px * k, 14.0);
        float I = tw / (k * k) * smoothstep(0.3, 1.0, dist);
        // keep clear of the suit volume edges fading with the box
        vec3 e = 1.0 - smoothstep(BOX * 0.8, BOX, abs(p - vec3(0.0, BOX.y + 0.05, 0.0)));
        vCol = col * I * e.x * e.y * e.z;
        gl_PointSize = px * 2.0;
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: /* glsl */ `
      varying vec3 vCol;
      void main() {
        vec2 q = gl_PointCoord * 2.0 - 1.0;
        float r2 = dot(q, q);
        if (r2 > 1.0) discard;
        float a = 1.0 - r2;
        gl_FragColor = vec4(vCol * a * a, 1.0);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
  });
  const dust = new THREE.Points(dg, dustMat);
  dust.frustumCulled = false;
  dust.renderOrder = 5;
  dust.name = 'dust';
  backdrop.add(dust);
}

// =====================================================================================
// sparks: instanced streaks with closed-form flight
// =====================================================================================
/** Height of the solid surface under (x, z): deck, plinth skirt or hangar floor. */
function surfaceY(x, z) {
  const r = Math.hypot(x, z);
  if (r < DECK_R) return 0;
  if (r < 1.5) return -0.085;
  if (r < 1.84) return TL.lerp(-0.15, -0.306, (r - 1.5) / 0.34);
  return FLOOR_Y;
}
function makeSparkSystem(U) {
  const O = [], V = [], T = [], M = [], C = [];
  const tmp = [0, 0, 0];
  const flight = (o, v, k, gs, tau, out) => {
    const e = Math.exp(-k * tau), a = (1 - e) / k, g = -9.81 * gs;
    out[0] = o[0] + v[0] * a; out[1] = o[1] + (v[1] - g / k) * a + (g * tau) / k; out[2] = o[2] + v[2] * a;
  };
  /**
   * Add one spark. Flight is p(τ) = o + (v - g/k)(1 - e^{-kτ})/k + gτ/k (linear drag k, gravity g).
   * The first ground contact is solved here once; the bounce becomes a second instance.
   */
  function emit(o, v, birth, life, { heat = 1, drag = 2.2, grav = 1, width = 0.003, period = 0, color = [1, 0.82, 0.55], bounces = 1, rnd = null, heat0 = 1 } = {}) {
    let kill = life;
    if (grav > 0) {
      const dt = 1 / 90;
      for (let tau = dt; tau < life; tau += dt) {
        flight(o, v, drag, grav, tau, tmp);
        if (tmp[1] < surfaceY(tmp[0], tmp[2])) {
          let lo = tau - dt, hi = tau;
          for (let it = 0; it < 12; it++) { const mid = (lo + hi) / 2; flight(o, v, drag, grav, mid, tmp); if (tmp[1] < surfaceY(tmp[0], tmp[2])) hi = mid; else lo = mid; }
          kill = lo;
          break;
        }
      }
    }
    O.push(o[0], o[1], o[2]); V.push(v[0], v[1], v[2]); T.push(birth, life, period, width); M.push(heat, drag, grav, kill); C.push(color[0], color[1], color[2]);
    if (kill < life - 0.03 && bounces > 0) {
      flight(o, v, drag, grav, kill, tmp);
      const e = Math.exp(-drag * kill), g = -9.81 * grav;
      const r1 = rnd ? rnd() : 0.5, r2 = rnd ? rnd() : 0.5, r3 = rnd ? rnd() : 0.5;
      const rest = 0.28 + 0.3 * r1;
      const v2 = [v[0] * e * (0.45 + 0.4 * r2) + (r3 - 0.5) * 0.5, -((v[1] - g / drag) * e + g / drag) * rest, v[2] * e * (0.45 + 0.4 * r2) + (r1 - 0.5) * 0.5];
      const remain = life - kill;
      const hot = heat * Math.pow(1 - kill / life, 1.3) * 0.8;
      emit([tmp[0], surfaceY(tmp[0], tmp[2]) + 0.004, tmp[2]], v2, birth + kill, remain * 0.85, { heat: hot, drag, grav, width: width * 0.85, period, color, bounces: bounces - 1, rnd });
    }
    void heat0;
  }
  function build() {
    const n = O.length / 3;
    const geo = new THREE.InstancedBufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute([0, -1, 0, 1, -1, 0, 1, 1, 0, 0, 1, 0], 3));
    geo.setIndex([0, 1, 2, 0, 2, 3]);
    geo.setAttribute('aO', new THREE.InstancedBufferAttribute(new Float32Array(O), 3));
    geo.setAttribute('aV', new THREE.InstancedBufferAttribute(new Float32Array(V), 3));
    geo.setAttribute('aT', new THREE.InstancedBufferAttribute(new Float32Array(T), 4));
    geo.setAttribute('aM', new THREE.InstancedBufferAttribute(new Float32Array(M), 4));
    geo.setAttribute('aC', new THREE.InstancedBufferAttribute(new Float32Array(C), 3));
    geo.instanceCount = n;
    const mat = new THREE.ShaderMaterial({
      uniforms: { uTime: U.time, uPx: U.px, uRes: U.res },
      vertexShader: /* glsl */ `
        attribute vec3 aO, aV, aC; attribute vec4 aT, aM;
        uniform float uTime, uPx; uniform vec2 uRes;
        varying vec2 vUv; varying vec3 vCol;
        vec3 posAt(float tau) {
          float k = aM.y; vec3 g = vec3(0.0, -9.81 * aM.z, 0.0);
          float e = exp(-k * tau);
          return aO + (aV - g / k) * ((1.0 - e) / k) + g * (tau / k);
        }
        void main() {
          float tau = uTime - aT.x;
          if (aT.z > 0.0) tau = mod(tau, aT.z);
          float u = tau / aT.y;
          gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
          if (tau < 0.0 || tau > aM.w || u > 1.0) return;
          float shutter = min(tau, 0.03);                 // streak = distance flown during the exposure
          vec4 c1 = projectionMatrix * viewMatrix * vec4(posAt(tau), 1.0);
          vec4 c0 = projectionMatrix * viewMatrix * vec4(posAt(tau - shutter), 1.0);
          if (c1.w < 0.06 || c0.w < 0.06) return;
          vec2 d = (c1.xy / c1.w - c0.xy / c0.w) * uRes;
          float len = length(d);
          d = len > 1e-4 ? d / len : vec2(1.0, 0.0);
          float wpx = aT.w * uPx / c1.w;
          float k = max(1.0, 0.75 / wpx);
          wpx *= k;
          vec4 c = mix(c0, c1, position.x);
          vec2 off = vec2(-d.y, d.x) * (position.y * wpx) + d * ((position.x * 2.0 - 1.0) * wpx);
          c.xy += off / uRes * 2.0 * c.w;
          gl_Position = c;
          float heat = aM.x * pow(1.0 - u, 1.3);
          // cooling metal: white-gold → orange → dull red
          vec3 ramp = mix(vec3(1.0, 0.16, 0.02), mix(vec3(1.0, 0.5, 0.12), vec3(1.0, 0.95, 0.8), smoothstep(0.45, 1.0, heat)), smoothstep(0.0, 0.45, heat));
          vCol = aC * ramp * (heat * 16.0 / k) * min(1.0, 4.0 / max(len, 4.0) + 0.55);
          vUv = position.xy;
        }`,
      fragmentShader: /* glsl */ `
        varying vec2 vUv; varying vec3 vCol;
        void main() {
          float across = 1.0 - vUv.y * vUv.y;
          float along = smoothstep(0.0, 0.75, vUv.x) * (1.0 - smoothstep(0.92, 1.0, vUv.x) * 0.5);
          gl_FragColor = vec4(vCol * across * across * (0.15 + 0.85 * along), 1.0);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }`,
      blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.frustumCulled = false;
    mesh.renderOrder = 7;
    mesh.name = 'sparks';
    return mesh;
  }
  return { emit, build, get count() { return O.length / 3; } };
}

/** Background welding: sparks are born inside fixed windows of a looping period. */
function emitWeld(sparks) {
  const rnd = prng(0x5eed1);
  const [wx, wy, wz] = WELD.pos;
  for (const [a, b] of WELD.windows) {
    const n = Math.round((b - a) * 150);
    for (let i = 0; i < n; i++) {
      const birth = TL.lerp(a, b, (i + rnd()) / n);
      const az = 0.9 + (rnd() - 0.5) * 2.6, el = 0.25 + rnd() * 1.0, sp = 1.6 + rnd() * rnd() * 4.2;
      const v = [Math.sin(az) * Math.cos(el) * sp, Math.sin(el) * sp, Math.cos(az) * Math.cos(el) * sp];
      sparks.emit([wx, wy, wz], v, birth, 0.6 + rnd() * 1.1, { heat: 0.7 + rnd() * 0.6, drag: 1.3, width: 0.0045, period: WELD.period, bounces: 1, rnd });
    }
  }
}
/** Hero beat: the turntable detents fire, and slow embers lift off around the suit. */
function emitHero(sparks) {
  const rnd = prng(0x4e20);
  const t0 = TL.PHASES.hero[0];
  for (let j = 0; j < 8; j++) {
    const a = ((j + 0.5) / 8) * TAU, [x, z] = pol(1.31, a);
    for (let i = 0; i < 16; i++) {
      const da = a + (rnd() - 0.5) * 0.9, sp = 1.2 + rnd() * 2.6, el = 0.7 + rnd() * 0.7;
      sparks.emit([x, -0.07, z], [Math.sin(da) * Math.cos(el) * sp, Math.sin(el) * sp, Math.cos(da) * Math.cos(el) * sp], t0 + 0.04 + rnd() * 0.28 + j * 0.012, 0.5 + rnd() * 0.8, { heat: 0.8 + rnd() * 0.5, drag: 2.0, width: 0.0028, rnd });
    }
  }
  for (let i = 0; i < 70; i++) { // embers: buoyant, heavy drag, long lived
    const a = rnd() * TAU, r = 0.75 + rnd() * 0.75, [x, z] = pol(r, a);
    sparks.emit([x, 0.02 + rnd() * 0.5, z], [(rnd() - 0.5) * 0.5, 0.25 + rnd() * 0.7, (rnd() - 0.5) * 0.5], t0 + 0.15 + rnd() * 1.4, 1.2 + rnd() * 1.6, { heat: 0.3 + rnd() * 0.35, drag: 1.1, grav: -0.03 - rnd() * 0.05, width: 0.0022, color: rnd() < 0.3 ? [0.5, 1.0, 0.95] : [1, 0.8, 0.5], bounces: 0 });
  }
}
/** Panel-lock bursts: sparks spray along the seam plane as each panel clacks home. */
function emitSnaps(sparks, snaps) {
  const n = Math.min(snaps.length, MAX_SNAPS);
  const per = Math.max(7, Math.min(26, Math.round(620 / Math.max(1, n))));
  const d = new THREE.Vector3(), r = new THREE.Vector3(), perp = new THREE.Vector3();
  for (let i = 0; i < n; i++) {
    const s = snaps[i], rnd = prng(0x51a9 + i * 131);
    d.copy(s.dir).normalize();
    for (let j = 0; j < per; j++) {
      r.set(rnd() * 2 - 1, rnd() * 2 - 1, rnd() * 2 - 1).normalize();
      perp.copy(r).addScaledVector(d, -r.dot(d));
      if (perp.lengthSq() < 1e-4) perp.set(d.y, -d.x, 0);
      perp.normalize();
      const sp = 0.9 + rnd() * rnd() * 3.4, out = 0.15 + rnd() * 0.75;
      const v = [(perp.x + d.x * out) * sp, (perp.y + d.y * out) * sp + 0.5, (perp.z + d.z * out) * sp];
      const o = [s.pos.x + perp.x * 0.05 + d.x * 0.02, s.pos.y + perp.y * 0.05 + d.y * 0.02, s.pos.z + perp.z * 0.05 + d.z * 0.02];
      sparks.emit(o, v, s.t + rnd() * 0.025, 0.3 + rnd() * rnd() * 0.9, { heat: 0.75 + rnd() * 0.6, drag: 2.6, width: 0.0026, rnd });
    }
  }
  return n;
}

// =====================================================================================
// assembly
// =====================================================================================
export function buildHangar(ctx) {
  const group = new THREE.Group();
  group.name = 'hangar';
  const backdrop = new THREE.Group();
  backdrop.name = 'backdrop';
  const platform = new THREE.Group();
  platform.name = 'turntable';
  group.add(backdrop, platform);

  const fogColor = new THREE.Color().setRGB(0.0075, 0.0115, 0.016);
  // shared uniforms, written once per frame in update()
  const U = {
    time: { value: 0 }, px: { value: 1000 }, res: { value: new THREE.Vector2(1280, 720) },
    weld: { value: 0 }, energy: { value: 0.35 }, rim: { value: 1 }, bake: { value: 0.45 },
    ringBake: { value: 1 }, ringLight: { value: new THREE.Color() },
    lampPos: { value: [] }, lampCol: { value: [] },
  };
  const maxAniso = ctx.renderer ? Math.min(8, ctx.renderer.capabilities.getMaxAnisotropy()) : 1;
  const tex = { noise: makeNoiseTexture(256, 0x9e3779b9), turned: makeTurnedTexture(512, 512, 0x85ebca6b) };
  tex.turned.anisotropy = maxAniso;

  const glows = makeGlowSystem(U, 640 + MAX_SNAPS);
  const lamps = [];
  const env = { group, backdrop, platform, U, tex, glow: glows.glow, lamps };

  const tt = buildTurntable(env);
  const arch = buildArchitecture(env);
  backdrop.add(glows.mesh);

  // floor lamps: the strongest practicals + one dynamic slot for the weld arc
  const floorLamps = lamps.filter((l) => l.floor > 0).sort((a, b) => b.floor - a.floor).slice(0, N_FLOOR_LAMPS - 1);
  for (let i = 0; i < N_FLOOR_LAMPS; i++) {
    const L = floorLamps[i];
    U.lampPos.value.push(L ? new THREE.Vector3(L.x, L.y, L.z) : new THREE.Vector3(0, -50, 0));
    U.lampCol.value.push(L ? new THREE.Color().setRGB(L.r * L.floor * 3.2, L.g * L.floor * 3.2, L.b * L.floor * 3.2) : new THREE.Color(0, 0, 0));
  }
  const WELD_SLOT = N_FLOOR_LAMPS - 1;
  U.lampPos.value[WELD_SLOT].set(WELD.pos[0], WELD.pos[1], WELD.pos[2]);

  // ---- lights (6) -------------------------------------------------------------------
  const aim = new THREE.Object3D();
  aim.position.fromArray(AIM);
  group.add(aim);
  const spot = (f, intensity, angle, penumbra) => {
    const l = new THREE.SpotLight(new THREE.Color().setRGB(f.col[0], f.col[1], f.col[2]), intensity, 0, angle, penumbra, 2);
    l.position.fromArray(f.pos);
    l.target = aim;
    group.add(l);
    return l;
  };
  const key = spot(FIX.key, 250, 0.3, 0.9);
  const rimA = spot(FIX.rimA, 420, 0.36, 0.7);
  // The one shadow caster is the strong back rim: its long shadows fall TOWARD the usual camera
  // side across the deck sheen, which grounds the suit far better than key shadows would.
  const caster = SHADOW_FROM_KEY ? key : rimA;
  caster.castShadow = true;
  caster.shadow.mapSize.set(2048, 2048);
  caster.shadow.camera.near = 2.5;
  caster.shadow.camera.far = 13;
  caster.shadow.bias = -0.0003;
  caster.shadow.normalBias = 0.012;
  const rimB = spot(FIX.rimB, 330, 0.36, 0.7);
  const fill = new THREE.HemisphereLight(new THREE.Color().setRGB(0.42, 0.55, 0.8), new THREE.Color().setRGB(0.02, 0.07, 0.07), 0.22);
  const uplight = new THREE.PointLight(new THREE.Color().setRGB(TEAL[0], TEAL[1], TEAL[2]), 0, 6, 2);
  uplight.position.set(0, -0.95, 0.25);
  const flash = new THREE.PointLight(new THREE.Color().setRGB(0.7, 1.0, 0.95), 0, 5, 2);
  flash.position.set(0, 1, 1);
  group.add(fill, uplight, flash);
  const BASE = { key: key.intensity, rimA: rimA.intensity, rimB: rimB.intensity };

  // ---- haze -------------------------------------------------------------------------
  const beams = [
    makeBeam(U, tex.noise, { from: FIX.rimA.pos, half: 10.5 * DEG, aperture: 0.15, far: 10.5, color: FIX.rimA.col, gain: 0.0052, g: 0.3 }),
    makeBeam(U, tex.noise, { from: FIX.rimB.pos, half: 10.5 * DEG, aperture: 0.15, far: 10.5, color: FIX.rimB.col, gain: 0.0042, g: 0.3 }),
    makeBeam(U, tex.noise, { from: FIX.key.pos, half: 13 * DEG, aperture: 0.22, far: 9.5, color: FIX.key.col, gain: 0.003, g: 0.3 }),
    makeBeam(U, tex.noise, { from: FIX.top.pos, half: 7 * DEG, aperture: 1.25, far: 7.2, color: FIX.top.col, gain: 0.005, g: 0.15, core: 0.2 }),
  ];
  beams[0].dust = 9; beams[1].dust = 7; beams[2].dust = 4; beams[3].dust = 28;
  buildHaze(env, beams);

  // ---- sparks: weld + hero are known now, lock bursts once the suit exists ---------------
  const sparks = makeSparkSystem(U);
  emitWeld(sparks);
  emitHero(sparks);
  let sparkMesh = null;
  let nSnap = -1;
  const snapT = new Float32Array(MAX_SNAPS), snapP = new Float32Array(MAX_SNAPS * 3);
  function initSnaps(snaps) {
    nSnap = emitSnaps(sparks, snaps);
    const k = TL.clamp(Math.sqrt(30 / Math.max(1, nSnap)), 0.4, 1); // many panels → smaller individual flashes
    for (let i = 0; i < nSnap; i++) {
      const s = snaps[i];
      snapT[i] = s.t;
      snapP.set([s.pos.x, s.pos.y, s.pos.z], i * 3);
      glows.glow(s.pos.x, s.pos.y, s.pos.z, [4 * k, 9 * k, 8.4 * k], 0.11, 2, s.t, 0.06);
      glows.glow(s.pos.x, s.pos.y, s.pos.z, [0.14 * k, 0.4 * k, 0.37 * k], 0.6, 2, s.t, 0.1);
    }
    sparkMesh = sparks.build();
    backdrop.add(sparkMesh);
  }

  // ---- per frame ----------------------------------------------------------------------
  let envApplied = null;
  const allEnvMats = [...tt.envMats, ...arch.envMats];
  const ENV_K = [0.26, 0.4, 0.5, 0.3, 0.35, 0.07]; // deck, deck-dark, plinth, structure, cables, floor
  const weldLevel = (t) => {
    const tp = ((t % WELD.period) + WELD.period) % WELD.period;
    let on = 0;
    for (const [a, b] of WELD.windows) if (tp >= a && tp <= b) on = 1;
    if (!on) return 0;
    const f = Math.floor(t * 60);
    return 0.35 + 0.65 * TL.hash(f * 1.37) * (0.6 + 0.4 * TL.hash(f * 0.11 + 5));
  };

  function update(state, frame) {
    const t = state.t;
    U.time.value = t;
    if (frame && frame.camera && frame.size) {
      U.px.value = frame.size.h * 0.5 * frame.camera.projectionMatrix.elements[5];
      U.res.value.set(frame.size.w, frame.size.h);
    }
    // reflection probe: our materials take scene.environment explicitly so each can scale it
    const sceneEnv = frame && frame.scene ? frame.scene.environment : null;
    if (sceneEnv !== envApplied) {
      envApplied = sceneEnv;
      allEnvMats.forEach((m, i) => { m.envMap = sceneEnv; m.envMapIntensity = ENV_K[i]; m.needsUpdate = true; });
    }
    if (nSnap < 0 && frame && frame.suit) initSnaps(frame.suit.snaps || []);

    // energy level of everything teal on the turntable (+ a kick each time a pulse leaves the feet)
    const kick = state.pulse >= 0 ? Math.exp(-state.pulse * 7) : 0;
    const lvl = 0.55 + 2.6 * state.energy + 2.2 * kick;
    tt.emisMat.color.setScalar(lvl * 1.15);
    tt.ringMat.color.setRGB(TEAL[0] * lvl * 1.5, TEAL[1] * lvl * 1.5, TEAL[2] * lvl * 1.5);
    tt.spillMat.color.setScalar(0.05 * lvl);
    U.ringBake.value = lvl * 0.5;
    U.ringLight.value.setRGB(TEAL[0], TEAL[1], TEAL[2]).multiplyScalar(lvl * 0.2);
    U.energy.value = lvl;
    uplight.intensity = 5.5 * lvl;

    // lock flashes: every snap adds a short pulse at its position
    let fsum = 0, fx = 0, fy = 0, fz = 0;
    for (let i = 0; i < nSnap; i++) {
      const tau = t - snapT[i];
      if (tau < 0 || tau > 0.5) continue;
      const w = Math.exp(-tau / 0.075);
      fsum += w; fx += snapP[i * 3] * w; fy += snapP[i * 3 + 1] * w; fz += snapP[i * 3 + 2] * w;
    }
    const fl = 1 - Math.exp(-fsum * 0.9);
    if (fsum > 1e-4) {
      fx /= fsum; fy /= fsum; fz /= fsum;
      const r = Math.hypot(fx, fz) || 1;
      flash.position.set(fx + (fx / r) * 0.45, fy + 0.1, fz + (fz / r) * 0.45);
    }
    flash.intensity = 26 * fl;

    // rims swell into the hero pose; the key eases down a touch while the core is exposed
    const hero = state.hero;
    const rimK = 1 + 0.4 * hero + 0.12 * fl;
    rimA.intensity = BASE.rimA * rimK;
    rimB.intensity = BASE.rimB * rimK;
    key.intensity = BASE.key * (1 - 0.14 * state.explode + 0.1 * hero);
    U.rim.value = rimK;
    beams[0].uniforms.uGain.value = beams[0].gain * (1 + 0.15 * hero + 0.1 * fl);
    beams[1].uniforms.uGain.value = beams[1].gain * (1 + 0.15 * hero + 0.1 * fl);

    // weld arc: flicker shared by the glow sprites and its floor reflection
    const w = weldLevel(t);
    U.weld.value = w;
    U.lampCol.value[WELD_SLOT].setRGB(5.5 * w, 7.5 * w, 12 * w);
  }

  return { group, platform, backdrop, fog: new THREE.FogExp2(fogColor, 0.034), background: fogColor.clone(), update };
}
