// Material library — look development for the exosuit.
//
// CONTRACT (unchanged from the placeholder):
//   createMaterials({ renderer }) → mat
//     mat.<name>      shared THREE.MeshPhysicalMaterial instances (never clone per mesh — every
//                     region shares these so the program / draw-state count stays tiny)
//     mat.uniforms    { uTime, uEnergy, uPulse, uCore, uThrust } shared by every program
//                     (+ uPulseGhost, internal: afterglow of the previous pulse sweep).
//                     uPulse follows state.pulse, except that after the final sweep it keeps
//                     running past 1.0 for a moment so the trail fades instead of popping off.
//     mat.update(state)  called once per frame by main.js; writes the uniforms from the timeline
//                     state. Pure function of `state` — no accumulation, safe to scrub.
//   buildDemo({ THREE, mat, kit }) → Group   material test bench (?demo=mat), see bottom of file.
//   Look-dev aid: --force=env:<k> (state.env) scales image-based lighting; env:0 = lights only.
//
// MATERIAL NAMES
//   titanium      brushed titanium, primary armour. Warm-neutral grey, real GGX anisotropy along
//                 the brushing, hairline + stray scratches, smudges, burnished edges.
//   titaniumDark  the same metal with a dark PVD finish — trim, secondary plates.
//   cobalt        deep cobalt metallic paint: metal-flake base under a clear coat (dust, swirl
//                 scratches and a touch of orange peel live in the coat; sharp edges chip to metal).
//   cobaltDark    near-navy version for recesses / under-plates.
//   frame         dark anodised inner structure, bead-blast grain, edges worn to bright metal.
//   steel         polished steel — pistons, shafts, bolts.
//   rubber        joint gaiters, cable sheaths, seals.
//   copper        windings, bus bars (brushed, slightly tarnished).
//   wire          braided dark cable loom.
//   wireTeal      braided accent-coloured signal wire (faint fibre-optic glow with uEnergy).
//   glass         dark semi-mirrored visor / lens with a faint teal inner glow.
//   thruster      heat-stained nozzle metal with straw / purple / blue temper colours.
//   thrusterGlow  additive plume / nozzle-interior glow. Soft toward silhouettes (facing ratio),
//                 so cones, discs and domes all read as volume. Driven by uThrust.
//   core          power-core plasma (animated, deterministic in uTime). Driven by uCore.
//   energy        teal energy lines. Driven by uEnergy, and by uPulse: a bright band that travels
//                 feet → head in WORLD space (uPulse 0..1 ↔ world y 0..2 m, -1 = off).
//   energyDim     fainter teal accents / status lights (same shader, about a third of the level).
//   -- extras (not in the placeholder; documented additions) --
//   carbon        clear-coated carbon-fibre weave: under-suit shells, liners, ducting.
//   copperCoil    copper with fine winding grooves around the mesh's LOCAL Y axis — for motor /
//                 solenoid windings modelled as cylinders or lathes about local Y.
//
// HOW THE SURFACES WORK
//   Suit geometry has no reliable UVs, so all surface detail is projected in OBJECT space
//   (triplanar) from four small tiling textures generated once from a seeded PRNG:
//     grain  512²  rg = slope of brushing + hairlines, b = brushing tone, a = hairline mask
//     wear  1024²  r = smudges, g = stray scratches, b = pits / dust, a = slow cloud
//     flake  512²  rg = per-flake tilt, b = per-flake sparkle, a = fine grain
//     weave  256²  rg = slope of a plain weave, b = height, a = which tow is on top
//   Detail is anchored to each mesh's local frame, so scratches travel with parts as they move.
//   (Instanced meshes: the instance matrix is applied first, so instances differ from each other.)
//   Cost per pixel: 3–6 texture fetches, no loops. The injections only touch colour, roughness,
//   shading normal and emission — never vertex positions — so shadow maps, depth pre-passes,
//   fog and tone mapping behave exactly as for a stock MeshPhysicalMaterial.
//
// NOTES FOR GEOMETRY BUILDERS
//   · Sharp bevels are detected from screen-space curvature (radius under ~5 mm): titanium edges
//     burnish, cobalt paint chips to bare metal, anodised frame wears bright. Smooth-shaded
//     bevels get this for free; flat-shaded geometry simply doesn't.
//   · Brushing wraps AROUND the mesh's local Y (like a lathe finish), so limbs / shells whose
//     long axis is local Y get a highlight streak running down their length.
//   · Meshes using thrusterGlow should set mesh.userData.castShadow = false (the material also
//     enforces it the first time such a mesh is drawn). thrusterGlow is additive with depthWrite
//     off — keep it out of any depth-only pre-pass.
//   · thrusterGlow fades by facing ratio only; shape the plume with geometry (a cone or teardrop
//     reads as a soft volume, a disc inside the nozzle as a hot throat).
//
// NOTES FOR POST / LIGHTING
//   Designed for ACES filmic tone mapping at exposure ≈ 1. Emission is linear HDR radiance:
//     energy      ≈ 0.5 idle (reads as saturated lit teal, below a bloom threshold of ~1),
//                 1.8–2.8 through the hero phase, and 12–15 inside the travelling pulse band
//     energyDim   about a third of that
//     core        filaments 4 (idle) … 11 (exploded view) … 30 (hero beat) over a dim teal body
//     thrusterGlow  up to ≈ 9 in the throat at full thrust
//   ACES pulls bright teal toward white, so the hot parts go white-teal on screen and the teal
//   identity of the glow comes from the bloom halo (the HDR value stays hue ≈ 174°).
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { computeState, DURATION } from '../core/timeline.js';

// =====================================================================================
// Procedural textures (deterministic, generated once)
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

// Adds amp × tileable value noise (cx × cy lattice cells) to out[size²].
function lattice(out, size, cx, cy, amp, rand) {
  const lat = new Float32Array(cx * cy);
  for (let i = 0; i < lat.length; i++) lat[i] = rand() * 2 - 1;
  const x0 = new Int32Array(size), x1 = new Int32Array(size), tx = new Float32Array(size);
  for (let x = 0; x < size; x++) {
    const f = (x * cx) / size, i = Math.floor(f), t = f - i;
    x0[x] = i % cx; x1[x] = (i + 1) % cx; tx[x] = t * t * (3 - 2 * t);
  }
  for (let y = 0; y < size; y++) {
    const f = (y * cy) / size, j = Math.floor(f), t = f - j, ty = t * t * (3 - 2 * t);
    const r0 = (j % cy) * cx, r1 = ((j + 1) % cy) * cx, row = y * size;
    for (let x = 0; x < size; x++) {
      const a = lat[r0 + x0[x]], b = lat[r1 + x0[x]];
      const top = a + (lat[r0 + x1[x]] - a) * tx[x];
      const bot = b + (lat[r1 + x1[x]] - b) * tx[x];
      out[row + x] += amp * (top + (bot - top) * ty);
    }
  }
}

function fbm(size, base, octaves, gain, rand) {
  const out = new Float32Array(size * size);
  let amp = 1;
  for (let o = 0; o < octaves; o++) {
    lattice(out, size, base << o, base << o, amp, rand);
    amp *= gain;
  }
  return normalise(out);
}

function normalise(buf) {
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < buf.length; i++) { if (buf[i] < lo) lo = buf[i]; if (buf[i] > hi) hi = buf[i]; }
  const k = 1 / Math.max(1e-6, hi - lo);
  for (let i = 0; i < buf.length; i++) buf[i] = (buf[i] - lo) * k;
  return buf;
}

// Anti-aliased, wrapping line with tapered ends, accumulated into buf (size must be a power of 2).
function scratch(buf, size, x, y, ang, len, weight) {
  const m = size - 1, steps = Math.max(2, Math.ceil(len * 2));
  const dx = Math.cos(ang) * 0.5, dy = Math.sin(ang) * 0.5;
  for (let i = 0; i <= steps; i++) {
    const k = weight * 0.5 * Math.pow(Math.sin((Math.PI * i) / steps), 0.4);
    const px = x + dx * i, py = y + dy * i;
    const ix = Math.floor(px), iy = Math.floor(py), fx = px - ix, fy = py - iy;
    const c0 = ix & m, c1 = (ix + 1) & m, r0 = (iy & m) * size, r1 = ((iy + 1) & m) * size;
    buf[r0 + c0] += k * (1 - fx) * (1 - fy);
    buf[r0 + c1] += k * fx * (1 - fy);
    buf[r1 + c0] += k * (1 - fx) * fy;
    buf[r1 + c1] += k * fx * fy;
  }
}

// Height field → slope packed in two 8-bit channels (0.5 = flat), auto-ranged to ±3.2 σ.
function slopes(h, size) {
  const m = size - 1, gx = new Float32Array(size * size), gy = new Float32Array(size * size);
  let sum = 0;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      gx[i] = h[y * size + ((x + 1) & m)] - h[y * size + ((x - 1) & m)];
      gy[i] = h[((y + 1) & m) * size + x] - h[((y - 1) & m) * size + x];
      sum += gx[i] * gx[i] + gy[i] * gy[i];
    }
  }
  const k = 0.5 / (3.2 * Math.sqrt(sum / (size * size * 2)) + 1e-9);
  return { gx, gy, k };
}

const to8 = (v) => Math.max(0, Math.min(255, Math.round(v * 255)));

function pack(size, fn) {
  const data = new Uint8Array(size * size * 4);
  for (let i = 0; i < size * size; i++) fn(i, data, i * 4);
  return data;
}

function makeGrain() {
  const S = 512, rand = prng(0x7171a11);
  // brushing: noise that is very fine across u and very long along v
  const h = new Float32Array(S * S);
  lattice(h, S, 512, 4, 0.55, rand);
  lattice(h, S, 256, 3, 0.5, rand);
  lattice(h, S, 512, 13, 0.36, rand);
  lattice(h, S, 128, 2, 0.34, rand);
  lattice(h, S, 64, 3, 0.2, rand);
  // hairlines: mostly along the brushing, a few strays
  const hair = new Float32Array(S * S);
  for (let i = 0; i < 520; i++) {
    const stray = rand() < 0.16;
    const ang = stray ? rand() * Math.PI : Math.PI / 2 + (rand() - 0.5) * 0.3;
    scratch(hair, S, rand() * S, rand() * S, ang, 24 + rand() * rand() * 300, 0.25 + 0.75 * rand());
  }
  const tone = normalise(h.slice());
  for (let i = 0; i < h.length; i++) h[i] -= 2.4 * Math.min(1, hair[i]);
  const { gx, gy, k } = slopes(h, S);
  return {
    size: S,
    data: pack(S, (i, d, o) => {
      d[o] = to8(0.5 + gx[i] * k); d[o + 1] = to8(0.5 + gy[i] * k); d[o + 2] = to8(tone[i]); d[o + 3] = to8(hair[i]);
    }),
  };
}

function makeWear() {
  const S = 1024, rand = prng(0x3ea7d05);
  const smudge = fbm(S, 4, 6, 0.56, rand);
  const cloud = fbm(S, 3, 5, 0.5, rand);
  const scr = new Float32Array(S * S);
  for (let i = 0; i < 240; i++) {
    scratch(scr, S, rand() * S, rand() * S, rand() * Math.PI * 2, 16 + rand() * rand() * 340, 0.2 + 0.6 * rand() * rand());
  }
  for (let c = 0; c < 8; c++) { // scuffs: tight bundles of parallel scratches
    const cx = rand() * S, cy = rand() * S, ang = rand() * Math.PI, n = 5 + Math.floor(rand() * 6);
    for (let j = 0; j < n; j++) {
      const off = (rand() - 0.5) * 26;
      scratch(scr, S, cx - Math.sin(ang) * off + (rand() - 0.5) * 30, cy + Math.cos(ang) * off + (rand() - 0.5) * 30, ang + (rand() - 0.5) * 0.08, 30 + rand() * 110, 0.2 + 0.45 * rand());
    }
  }
  const pit = new Float32Array(S * S);
  for (let i = 0; i < 3400; i++) {
    const big = rand() < 0.12;
    scratch(pit, S, rand() * S, rand() * S, rand() * 6.283, big ? 1.6 : 0.6, (big ? 1.5 : 1) * (0.4 + rand()));
  }
  return {
    size: S,
    data: pack(S, (i, d, o) => {
      const s = smudge[i];
      d[o] = to8(s * s * (3 - 2 * s)); d[o + 1] = to8(scr[i]); d[o + 2] = to8(pit[i]); d[o + 3] = to8(cloud[i]);
    }),
  };
}

function makeFlake() {
  const S = 512, N = 128, rand = prng(0xf1a6e55);
  const px = new Float32Array(N * N), py = new Float32Array(N * N), tx = new Float32Array(N * N), ty = new Float32Array(N * N), br = new Float32Array(N * N);
  for (let i = 0; i < N * N; i++) {
    px[i] = rand(); py[i] = rand();
    const a = rand() * Math.PI * 2, r = Math.sqrt(rand());
    tx[i] = Math.cos(a) * r; ty[i] = Math.sin(a) * r;
    br[i] = Math.pow(rand(), 3.2);
  }
  const grain = new Float32Array(S * S);
  // fine grain only — any low-frequency content would show up as a repeating blotch
  lattice(grain, S, 512, 512, 1, rand);
  lattice(grain, S, 256, 256, 0.8, rand);
  lattice(grain, S, 128, 128, 0.45, rand);
  normalise(grain);
  const cell = S / N;
  return {
    size: S,
    data: pack(S, (i, d, o) => {
      const x = (i % S) / cell, y = Math.floor(i / S) / cell;
      const cx = Math.floor(x), cy = Math.floor(y);
      let best = 1e9, id = 0;
      for (let j = -1; j <= 1; j++) {
        for (let k = -1; k <= 1; k++) {
          const c = ((cy + j + N) % N) * N + ((cx + k + N) % N);
          const ddx = cx + k + px[c] - x, ddy = cy + j + py[c] - y, dd = ddx * ddx + ddy * ddy;
          if (dd < best) { best = dd; id = c; }
        }
      }
      d[o] = to8(0.5 + tx[id] * 0.5); d[o + 1] = to8(0.5 + ty[id] * 0.5); d[o + 2] = to8(br[id]); d[o + 3] = to8(grain[i]);
    }),
  };
}

function makeWeave() {
  const S = 256, N = 16;
  const h = new Float32Array(S * S), top = new Float32Array(S * S);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      // plain weave on a 45° lattice: tows alternate over / under
      const a = ((x + y) * N) / S, b = ((x - y) * N) / S;
      const i = Math.floor(a), j = Math.floor(b), u = a - i, v = b - j;
      const over = ((i + j) & 1) === 0;
      const across = over ? v : u, along = over ? u : v;
      const round = Math.sqrt(Math.max(0, 1 - Math.pow(2 * across - 1, 2)));
      h[y * S + x] = round * (0.55 + 0.45 * Math.sin(Math.PI * along));
      top[y * S + x] = over ? 1 : 0;
    }
  }
  const { gx, gy, k } = slopes(h, S);
  return {
    size: S,
    data: pack(S, (i, d, o) => {
      d[o] = to8(0.5 + gx[i] * k); d[o + 1] = to8(0.5 + gy[i] * k); d[o + 2] = to8(h[i]); d[o + 3] = to8(top[i]);
    }),
  };
}

function makeTextures(renderer) {
  const maxAniso = renderer && renderer.capabilities ? renderer.capabilities.getMaxAnisotropy() : 1;
  const tex = ({ size, data }, aniso) => {
    const t = new THREE.DataTexture(data, size, size, THREE.RGBAFormat, THREE.UnsignedByteType);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.magFilter = THREE.LinearFilter;
    t.minFilter = THREE.LinearMipmapLinearFilter;
    t.generateMipmaps = true;
    t.anisotropy = Math.min(aniso, maxAniso);
    t.colorSpace = THREE.NoColorSpace;
    t.needsUpdate = true;
    return t;
  };
  return { grain: tex(makeGrain(), 8), wear: tex(makeWear(), 4), flake: tex(makeFlake(), 4), weave: tex(makeWeave(), 4) };
}

// =====================================================================================
// Shader injection
// =====================================================================================
const VERT_PARS = /* glsl */ `
varying vec3 vExoPos;   // mesh-local position (instance matrix applied)
varying vec3 vExoNrm;   // mesh-local normal
varying vec3 vExoWorld; // world position (pulse band lives in world space)
`;
const VERT_MAIN = /* glsl */ `
{
  vec4 exoP = vec4( transformed, 1.0 );
  vec3 exoN = objectNormal;
  #ifdef USE_BATCHING
    exoP = batchingMatrix * exoP;
    exoN = mat3( batchingMatrix ) * exoN;
  #endif
  #ifdef USE_INSTANCING
    exoP = instanceMatrix * exoP;
    exoN = mat3( instanceMatrix ) * exoN;
  #endif
  vExoPos = exoP.xyz;
  vExoNrm = exoN;
  vExoWorld = ( modelMatrix * exoP ).xyz;
}
`;
const FRAG_PARS = /* glsl */ `
varying vec3 vExoPos;
varying vec3 vExoNrm;
varying vec3 vExoWorld;
uniform mat3 normalMatrix;
uniform float uTime;
uniform float uEnergy;
uniform float uPulse;
uniform float uPulseGhost;
uniform float uCore;
uniform float uThrust;
uniform sampler2D uExoA;
uniform sampler2D uExoB;
const vec3 EXO_TEAL = vec3( 0.0, 1.0, 0.80 );      // hue ≈ 174° after the display transform
const vec3 EXO_TEAL_HOT = vec3( 0.42, 1.0, 0.90 ); // what it burns out to
vec3 exoSgn; // sign of the mesh-local normal: opposite faces sample different parts of a tile
vec4 exoTri( sampler2D t, vec3 p, vec3 w ) {
  return texture2D( t, p.zy + exoSgn.x * vec2( 0.31, 0.17 ) ) * w.x
       + texture2D( t, p.xz + exoSgn.y * vec2( 0.23, 0.41 ) ) * w.y
       + texture2D( t, p.xy + exoSgn.z * vec2( 0.19, 0.47 ) ) * w.z;
}
// Travelling pulse: a tight bright band at world height 2·uPulse with an afterglow trailing
// below it (and the fading tail of the previous sweep, so the wrap from head to feet never pops).
float exoPulse( float tail ) {
  float d = vExoWorld.y * 0.5 - uPulse;
  float band = exp( - d * d * 900.0 );
  float trail = d < 0.0 ? exp( d * 6.5 ) : 0.0;
  float ghost = exp( min( d - 1.0, 0.0 ) * 6.5 ) * uPulseGhost;
  return step( 0.0, uPulse ) * ( band + tail * ( trail * ( 1.0 - band ) + ghost ) );
}
`;
// Runs right after <color_fragment>: everything shared by the per-material snippets.
const FRAG_SETUP = /* glsl */ `
vec3 exoN = normalize( vExoNrm );
exoSgn = sign( exoN );
vec3 exoW = pow( abs( exoN ), vec3( 6.0 ) );
exoW /= ( exoW.x + exoW.y + exoW.z );
vec3 exoV = normalize( vViewPosition );
#ifdef FLAT_SHADED
  vec3 exoGN = normalize( cross( dFdx( vViewPosition ), dFdy( vViewPosition ) ) );
#else
  vec3 exoGN = normalize( vNormal );
#endif
float exoNdV = abs( dot( exoGN, exoV ) );
float exoPix = length( fwidth( vViewPosition ) );            // surface metres covered by this pixel
float exoTurnPx = length( fwidth( exoGN ) );                 // how far the normal turns across this pixel
float exoCurv = exoTurnPx / max( exoPix, 1e-6 );              // curvature in 1/m: bevels ≫ panels
// sharp bevels only — and only while the bevel is resolved on screen; once it is thinner than a
// pixel the estimate is noise, so the wear fades out instead of sparkling along every edge
float exoEdge = smoothstep( 170.0, 420.0, exoCurv ) * smoothstep( 0.9, 0.3, exoTurnPx );
// very gentle long-wave unevenness: real panels are never optically flat
vec3 exoQ = vExoPos * 23.0;
vec3 exoWav = vec3( sin( exoQ.y * 1.3 + 1.7 * sin( exoQ.z * 0.7 + exoQ.x * 0.4 ) ), sin( exoQ.z * 1.1 + 1.7 * sin( exoQ.x * 0.8 + exoQ.y * 0.3 ) ), sin( exoQ.x * 1.2 + 1.7 * sin( exoQ.y * 0.9 + exoQ.z * 0.5 ) ) );
#ifdef EXO_A
  vec3 exoPA = vExoPos * EXO_A;
  // Projections are oriented so the texture's v axis (the brushing direction of the grain
  // texture) wraps AROUND local Y: z on ±X faces, x on ±Z and ±Y faces — a lathe-turned finish.
  vec4 exoAx = texture2D( uExoA, exoPA.yz + exoSgn.x * vec2( 0.37, 0.0 ) );
  vec4 exoAy = texture2D( uExoA, exoPA.zx + exoSgn.y * vec2( 0.29, 0.0 ) );
  vec4 exoAz = texture2D( uExoA, exoPA.yx + exoSgn.z * vec2( 0.43, 0.0 ) );
  vec4 exoA = exoAx * exoW.x + exoAy * exoW.y + exoAz * exoW.z;
  // slope (rg) of each projection, re-expressed along the mesh axes that projection spans
  vec3 exoGA = exoW.x * vec3( 0.0, exoAx.r - 0.5, exoAx.g - 0.5 )
             + exoW.y * vec3( exoAy.g - 0.5, 0.0, exoAy.r - 0.5 )
             + exoW.z * vec3( exoAz.g - 0.5, exoAz.r - 0.5, 0.0 );
  // 0 while the detail is resolved on screen → 1 once mip-mapping has averaged it away
  float exoFadeA = saturate( log2( max( exoPix * EXO_A * EXO_A_SIZE, 1.0 ) ) / 3.0 );
#endif
#ifdef EXO_B
  // slow metre-scale drift of the lookup, so the tile never lines up into a visible grid on
  // large surfaces (platform tops, long shells)
  vec3 exoPB = vExoPos * EXO_B + EXO_B_OFF + 0.3 * sin( vExoPos.yzx * vec3( 2.9, 2.3, 3.4 ) + vec3( 0.0, 1.3, 2.6 ) );
  vec4 exoB = exoTri( uExoB, exoPB, exoW );
#endif
`;
// Brushed metals: overrides three's (UV-derived) anisotropy frame with the object-space
// brushing direction. Grooves run around local Y (see the projection note above) — the same
// axes the grain texture is projected along, so the highlight and the visible brushing always
// agree. A light therefore draws a streak ALONG a limb's axis, crisp across its curvature.
const FRAG_ANISO = /* glsl */ `
#ifdef USE_ANISOTROPY
{
  // circumferential direction around local Y; flat ±Y faces fall back to local X (matching the
  // grain projection used on those faces)
  vec3 exoGroove = vec3( exoN.z, 0.0, - exoN.x ) + vec3( smoothstep( 0.9, 1.0, abs( exoN.y ) ), 0.0, 0.0 );
  exoGroove = normalize( normalMatrix * exoGroove );
  vec3 exoB3 = exoGroove - normal * dot( exoGroove, normal );
  float exoBl = length( exoB3 );
  exoB3 = exoBl > 1e-4 ? exoB3 / exoBl : vec3( 1.0, 0.0, 0.0 );
  material.anisotropyB = exoB3;                    // along the grooves (smooth direction)
  material.anisotropyT = cross( exoB3, normal );   // across the grooves (highlight stretches here)
  material.anisotropy *= smoothstep( 0.0, 0.35, exoBl );
  material.alphaT = mix( pow2( material.roughness ), 1.0, pow2( material.anisotropy ) );
}
#endif
`;
// A whisper of the travelling pulse on the armour itself, as if the energy band lit the plates.
const armourPulse = (gain) => /* glsl */ `
totalEmissiveRadiance += EXO_TEAL * ( exoPulse( 0.12 ) * ${gain.toFixed(3)} * ( 0.2 + 0.8 * pow( 1.0 - exoNdV, 2.0 ) ) );
`;

const f = (x) => (Number.isInteger(x) ? x.toFixed(1) : String(x));
const v3 = (a) => `vec3( ${a.map(f).join(', ')} )`;

/**
 * Attach object-space detail to a MeshPhysicalMaterial.
 * cfg: { key, A:{tex,scale}, B:{tex,scale,off,scroll}, albedo, surface, normal, coat, emissive,
 *        post, final, unlit, aniso } — each stage is a GLSL snippet spliced into the stock shader.
 */
function detail(material, uniforms, cfg) {
  const defs = [];
  if (cfg.A) defs.push(`#define EXO_A ${f(cfg.A.scale)}`, `#define EXO_A_SIZE ${f(cfg.A.tex.image.width)}`);
  if (cfg.B) defs.push(`#define EXO_B ${f(cfg.B.scale)}`, `#define EXO_B_OFF ( ${v3(cfg.B.off || [0, 0, 0])}${cfg.B.scroll ? ' + ' + cfg.B.scroll : ''} )`);
  const local = { uExoA: { value: cfg.A ? cfg.A.tex : null }, uExoB: { value: cfg.B ? cfg.B.tex : null } };
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms, local);
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\n' + VERT_PARS)
      .replace('#include <project_vertex>', '#include <project_vertex>\n' + VERT_MAIN);
    let fs = shader.fragmentShader
      .replace('#include <common>', '#include <common>\n' + defs.join('\n') + '\n' + FRAG_PARS)
      .replace('#include <color_fragment>', '#include <color_fragment>\n' + FRAG_SETUP + (cfg.albedo || ''))
      .replace('#include <metalnessmap_fragment>', '#include <metalnessmap_fragment>\n' + (cfg.surface || ''))
      .replace('#include <normal_fragment_maps>', '#include <normal_fragment_maps>\n' + (cfg.normal || ''))
      .replace('#include <clearcoat_normal_fragment_maps>', '#include <clearcoat_normal_fragment_maps>\n' + (cfg.coat || ''))
      .replace('#include <emissivemap_fragment>', '#include <emissivemap_fragment>\n' + (cfg.emissive || ''))
      .replace('#include <lights_physical_fragment>', '#include <lights_physical_fragment>\n' + (cfg.aniso ? FRAG_ANISO : '') + (cfg.post || ''))
      .replace('#include <opaque_fragment>', (cfg.final || '') + '\n#include <opaque_fragment>');
    if (cfg.unlit) {
      // pure emitters: skip the whole light / shadow / IBL accumulation
      fs = fs.replace('#include <lights_fragment_begin>', '').replace('#include <lights_fragment_maps>', '').replace('#include <lights_fragment_end>', '');
    }
    shader.fragmentShader = fs;
  };
  material.customProgramCacheKey = () => 'exo:' + cfg.key;
  material.name = cfg.key;
  return material;
}

// ---- shared snippet families ----------------------------------------------------------
// Brushed / machined metal. o: { streak, bump, wave, scratch, edge, smudge, lanes, extraAlbedo, extraSurface }
function brushed(o) {
  return {
    aniso: true,
    albedo: /* glsl */ `
      float exoScr = max( exoB.g, exoA.a * 0.55 ) * ${f(o.scratch)};
      // machining lanes: broad bands of slightly different sheen running with the brushing —
      // what survives of the finish at distances where the grain itself has blurred away
      float exoAcross = mix( vExoPos.y, vExoPos.z, exoW.y );
      float exoLane = 0.6 * sin( exoAcross * 173.0 + 2.2 * sin( exoAcross * 31.0 ) ) + 0.4 * sin( exoAcross * 67.0 + 1.7 * sin( exoAcross * 19.0 + 1.0 ) );
      diffuseColor.rgb *= 1.0 + ${f((o.lanes || 0) * 0.35)} * exoLane;
      diffuseColor.rgb *= 1.0 + ${f(o.streak)} * ( exoA.b - 0.5 ) * ( 1.0 - 0.6 * exoFadeA );  // brushing lanes differ in tone
      diffuseColor.rgb *= mix( vec3( 1.04, 1.0, 0.95 ), vec3( 0.96, 1.0, 1.05 ), exoB.a );      // slow warm ↔ cool oxide drift
      diffuseColor.rgb *= 1.0 - 0.35 * exoB.b;                                                   // pits, dust specks
      diffuseColor.rgb = mix( diffuseColor.rgb, min( diffuseColor.rgb * 1.4 + 0.06, vec3( 0.92 ) ), exoScr * 0.5 ); // scratches cut to bright metal
      diffuseColor.rgb = mix( diffuseColor.rgb, min( diffuseColor.rgb * 1.35 + 0.05, vec3( 0.92 ) ), exoEdge * ${f(o.edge)} ); // burnished edges
      ${o.extraAlbedo || ''}
    `,
    surface: /* glsl */ `
      roughnessFactor *= ${f(1 - o.smudge * 0.45)} + ${f(o.smudge)} * exoB.r;      // smudges, handling marks
      roughnessFactor *= 1.0 + ${f(o.lanes || 0)} * exoLane;                        // machining lanes
      roughnessFactor = mix( roughnessFactor, 0.15, exoScr * 0.6 );
      roughnessFactor *= 1.0 - 0.35 * exoEdge * ${f(o.edge)};
      roughnessFactor += 0.07 * exoFadeA + 0.2 * exoB.b;          // detail lost to distance becomes roughness
      ${o.extraSurface || ''}
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * ${f(-o.bump)} + exoWav * ${f(o.wave)} ) );
    `,
  };
}

// Braided / woven dielectrics (cable looms, carbon weave).
function woven(o) {
  return {
    albedo: /* glsl */ `
      float exoTow = mix( 0.5, exoA.a, 1.0 - exoFadeA );
      diffuseColor.rgb *= ( 0.5 + 0.75 * mix( 0.62, exoA.b, 1.0 - exoFadeA ) ) * ( 1.0 + ${f(o.towContrast)} * ( exoTow - 0.5 ) );
      diffuseColor.rgb += vec3( 0.03, 0.028, 0.025 ) * exoB.r * exoB.r * ${f(o.dust)};
    `,
    surface: /* glsl */ `
      roughnessFactor += 0.18 * ( 1.0 - exoA.b ) * ( 1.0 - exoFadeA ) + 0.12 * exoB.r;
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * ${f(-o.bump)} ) );
    `,
  };
}

// =====================================================================================
// Library
// =====================================================================================
export function createMaterials({ renderer } = {}) {
  // Shared uniforms, written once per frame in update(state).
  const uniforms = {
    uTime: { value: 0 },
    uEnergy: { value: 0.35 }, // global energy-line level
    uPulse: { value: -1 }, // travelling pulse position 0..1 (feet → head), -1 = off (runs on past 1 while the last trail fades)
    uCore: { value: 0.6 }, // power-core glow level
    uThrust: { value: 0 }, // thruster plume level
    uPulseGhost: { value: 0 }, // 1 once a previous sweep exists (its afterglow keeps fading over the wrap)
  };
  const tex = makeTextures(renderer);
  const P = (o) => new THREE.MeshPhysicalMaterial(o);
  const lin = (r, g, b) => new THREE.Color().setRGB(r, g, b); // linear working-space colour
  const D = (m, cfg) => detail(m, uniforms, cfg);
  const GRAIN = (scale) => ({ tex: tex.grain, scale });
  const WEAR = (scale, off) => ({ tex: tex.wear, scale, off });
  const FLAKE = (scale) => ({ tex: tex.flake, scale });
  const WEAVE = (scale) => ({ tex: tex.weave, scale });

  const mat = { uniforms };

  // ---- brushed titanium ---------------------------------------------------------------
  mat.titanium = D(P({ color: lin(0.53, 0.5, 0.46), metalness: 1, roughness: 0.3, anisotropy: 0.45 }), {
    key: 'titanium', A: GRAIN(13.0), B: WEAR(2.6, [0.11, 0.37, 0.73]),
    ...brushed({ streak: 0.3, bump: 0.5, wave: 0.006, scratch: 0.8, edge: 0.7, smudge: 0.1, lanes: 0.035 }),
    emissive: armourPulse(0.32),
  });
  mat.titaniumDark = D(P({ color: lin(0.2, 0.196, 0.2), metalness: 1, roughness: 0.27, anisotropy: 0.45 }), {
    key: 'titaniumDark', A: GRAIN(15.0), B: WEAR(2.9, [0.53, 0.19, 0.41]),
    ...brushed({ streak: 0.34, bump: 0.5, wave: 0.006, scratch: 0.9, edge: 0.9, smudge: 0.1, lanes: 0.035 }),
    emissive: armourPulse(0.32),
  });

  // ---- cobalt metallic paint -------------------------------------------------------------
  const paint = (flop) => ({
    albedo: /* glsl */ `
      // two-tone metallic: saturated face colour, inkier flop toward grazing angles
      diffuseColor.rgb = mix( diffuseColor.rgb * ${v3(flop)}, diffuseColor.rgb, pow( exoNdV, 0.75 ) );
      diffuseColor.rgb *= 0.94 + 0.36 * exoA.b * ( 1.0 - exoFadeA ) * ( 0.4 + 1.2 * exoB.a ); // individual flakes catch the light
      diffuseColor.rgb = mix( diffuseColor.rgb, vec3( 0.36, 0.38, 0.4 ), exoB.b * 0.16 );   // dust on the lacquer
      // lacquer and colour worn through to bare titanium on sharp edges
      float exoChip = smoothstep( 0.5, 0.66, exoEdge * ( 0.12 + 0.75 * exoB.r + 0.45 * exoA.a ) );
      diffuseColor.rgb = mix( diffuseColor.rgb, vec3( 0.6, 0.57, 0.53 ), exoChip );
    `,
    surface: /* glsl */ `
      metalnessFactor = mix( metalnessFactor, 1.0, exoChip );
      roughnessFactor = mix( roughnessFactor + 0.12 * ( exoA.a - 0.5 ) * ( 1.0 - exoFadeA ), 0.3, exoChip );
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * ( -0.24 * ( 1.0 - exoChip ) ) ) );
    `,
    coat: /* glsl */ `
      clearcoatNormal = normalize( clearcoatNormal + normalMatrix * ( exoWav * 0.0016 ) );
    `,
    post: /* glsl */ `
      material.clearcoat *= 1.0 - exoChip;
      // fingerprints / haze, swirl scratches and dust all live in the clear coat
      material.clearcoatRoughness = clamp( material.clearcoatRoughness * ( 0.97 + 0.07 * exoB.r ) + 0.24 * exoB.g + 0.12 * exoB.b, 0.0525, 1.0 );
    `,
    emissive: armourPulse(0.36),
  });
  mat.cobalt = D(P({ color: lin(0.012, 0.085, 0.5), metalness: 0.88, roughness: 0.36, clearcoat: 1, clearcoatRoughness: 0.07 }), {
    key: 'cobalt', A: FLAKE(39.0), B: WEAR(2.4, [0.29, 0.61, 0.07]), ...paint([0.4, 0.5, 0.8]),
  });
  mat.cobaltDark = D(P({ color: lin(0.008, 0.035, 0.2), metalness: 0.88, roughness: 0.4, clearcoat: 0.85, clearcoatRoughness: 0.1 }), {
    key: 'cobaltDark', A: FLAKE(39.0), B: WEAR(2.4, [0.71, 0.13, 0.47]), ...paint([0.45, 0.55, 0.85]),
  });

  // ---- inner structure ---------------------------------------------------------------------
  mat.frame = D(P({ color: lin(0.052, 0.055, 0.068), metalness: 0.92, roughness: 0.47 }), {
    key: 'frame', A: FLAKE(17.0), B: WEAR(3.1, [0.4, 0.8, 0.2]),
    albedo: /* glsl */ `
      diffuseColor.rgb *= 0.8 + 0.4 * exoB.a;
      // anodising rubbed through to bright metal on edges and along scratches
      float exoWorn = saturate( smoothstep( 0.4, 0.62, exoEdge * ( 0.45 + 0.8 * exoB.r ) ) + 0.12 * exoB.g );
      diffuseColor.rgb = mix( diffuseColor.rgb, vec3( 0.5, 0.51, 0.54 ), exoWorn );
    `,
    surface: /* glsl */ `
      roughnessFactor *= 0.8 + 0.45 * exoB.r;
      roughnessFactor = mix( roughnessFactor + 0.1 * ( exoA.a - 0.5 ), 0.26, exoWorn );
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * -0.22 ) );
    `,
  });
  mat.steel = D(P({ color: lin(0.74, 0.745, 0.76), metalness: 1, roughness: 0.15, anisotropy: 0.2 }), {
    key: 'steel', A: GRAIN(18.0), B: WEAR(3.4, [0.9, 0.3, 0.6]),
    ...brushed({ streak: 0.1, bump: 0.16, wave: 0.004, scratch: 0.7, edge: 0.3, smudge: 0.3 }),
  });
  mat.rubber = D(P({ color: lin(0.012, 0.012, 0.014), metalness: 0, roughness: 0.78 }), {
    key: 'rubber', A: FLAKE(14.0), B: WEAR(2.2, [0.6, 0.2, 0.9]),
    albedo: /* glsl */ `
      diffuseColor.rgb *= 0.75 + 0.5 * exoA.a;
      diffuseColor.rgb += vec3( 0.04, 0.037, 0.033 ) * ( exoB.r * exoB.r + 0.5 * exoB.b ); // dust settles on rubber
    `,
    surface: /* glsl */ `
      roughnessFactor = saturate( roughnessFactor - 0.16 + 0.3 * exoB.a );
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * -0.3 ) );
    `,
  });
  const copperLook = {
    streak: 0.2, bump: 0.3, wave: 0.005, scratch: 0.8, edge: 0.4, smudge: 0.25,
    extraAlbedo: /* glsl */ `
      float exoTarnish = smoothstep( 0.45, 0.9, exoB.a ) * 0.4;
      diffuseColor.rgb = mix( diffuseColor.rgb, diffuseColor.rgb * vec3( 0.52, 0.4, 0.36 ), exoTarnish );
    `,
    extraSurface: /* glsl */ `roughnessFactor += 0.18 * exoTarnish;`,
  };
  mat.copper = D(P({ color: lin(0.92, 0.5, 0.32), metalness: 1, roughness: 0.3, anisotropy: 0.35 }), {
    key: 'copper', A: GRAIN(16.0), B: WEAR(3.0, [0.2, 0.5, 0.8]), ...brushed(copperLook),
  });
  const coil = brushed(copperLook);
  mat.copperCoil = D(P({ color: lin(0.9, 0.47, 0.29), metalness: 1, roughness: 0.3 }), {
    key: 'copperCoil', A: GRAIN(16.0), B: WEAR(3.0, [0.7, 0.2, 0.4]), ...coil,
    aniso: false,
    albedo: coil.albedo + /* glsl */ `
      // enamelled wire wound around local Y: one turn every 1.4 mm, faded out before it can alias
      float exoTurn = vExoPos.y * 4488.0;
      float exoTurnFade = saturate( 1.0 - fwidth( exoTurn ) / 2.4 );
      diffuseColor.rgb *= mix( 0.82, 0.62 + 0.38 * ( 0.5 + 0.5 * sin( exoTurn ) ), exoTurnFade ); // dark gaps between turns
    `,
    normal: /* glsl */ `
      normal = normalize( normal + normalMatrix * ( exoGA * -0.2 + vec3( 0.0, cos( exoTurn ) * 0.55 * exoTurnFade, 0.0 ) ) );
    `,
  });

  // ---- cable looms and composites -------------------------------------------------------
  mat.wire = D(P({ color: lin(0.03, 0.032, 0.038), metalness: 0, roughness: 0.52 }), {
    key: 'wire', A: WEAVE(48.0), B: WEAR(3.0, [0.3, 0.3, 0.3]), ...woven({ bump: 0.75, towContrast: 0.35, dust: 1.0 }),
  });
  mat.wireTeal = D(P({ color: lin(0.0, 0.17, 0.16), metalness: 0, roughness: 0.42 }), {
    key: 'wireTeal', A: WEAVE(48.0), B: WEAR(3.0, [0.8, 0.1, 0.5]), ...woven({ bump: 0.7, towContrast: 0.3, dust: 0.4 }),
    emissive: /* glsl */ `
      totalEmissiveRadiance += EXO_TEAL * ( 0.01 + 0.12 * uEnergy * uEnergy + 0.5 * exoPulse( 0.4 ) ) * ( 0.4 + 0.6 * exoA.b );
    `,
  });
  mat.carbon = D(P({ color: lin(0.022, 0.023, 0.026), metalness: 0, roughness: 0.42, clearcoat: 1, clearcoatRoughness: 0.08 }), {
    key: 'carbon', A: WEAVE(26.0), B: WEAR(2.5, [0.15, 0.75, 0.35]),
    albedo: /* glsl */ `
      // tows running one way look brighter than the other, and swap as the view moves around them
      float exoTow = mix( 0.5, exoA.a, 1.0 - exoFadeA );
      float exoSwap = 0.5 + 0.5 * dot( exoGN.xy, vec2( 0.8, 0.6 ) );
      diffuseColor.rgb *= 0.35 + 1.7 * mix( exoTow, 1.0 - exoTow, exoSwap ) * mix( 0.6, exoA.b, 1.0 - exoFadeA );
    `,
    surface: /* glsl */ `roughnessFactor += 0.15 * ( 1.0 - exoA.b ) * ( 1.0 - exoFadeA );`,
    normal: /* glsl */ `normal = normalize( normal + normalMatrix * ( exoGA * -0.45 ) );`,
    coat: /* glsl */ `clearcoatNormal = normalize( clearcoatNormal + normalMatrix * ( exoWav * 0.002 ) );`,
    post: /* glsl */ `material.clearcoatRoughness = clamp( material.clearcoatRoughness * ( 0.6 + 1.2 * exoB.r ) + 0.25 * exoB.g + 0.12 * exoB.b, 0.0525, 1.0 );`,
  });

  // ---- visor glass -------------------------------------------------------------------------
  mat.glass = D(P({ color: lin(0.07, 0.095, 0.12), metalness: 0.9, roughness: 0.035, clearcoat: 1, clearcoatRoughness: 0.03 }), {
    key: 'glass', B: WEAR(3.6, [0.45, 0.05, 0.65]),
    surface: /* glsl */ `roughnessFactor += 0.07 * exoB.r * exoB.r + 0.25 * exoB.g;`,
    coat: /* glsl */ `clearcoatNormal = normalize( clearcoatNormal + normalMatrix * ( exoWav * 0.001 ) );`,
    post: /* glsl */ `material.clearcoatRoughness = clamp( material.clearcoatRoughness + 0.1 * exoB.r * exoB.r + 0.35 * exoB.g + 0.1 * exoB.b, 0.0525, 1.0 );`,
    emissive: /* glsl */ `
      // faint inner glow: strongest looking straight in, with fine display scan-lines up close
      float exoScan = vExoPos.y * 2100.0;
      float exoScanAmt = 0.22 * saturate( 1.0 - fwidth( exoScan ) / 2.0 ) * sin( exoScan );
      float exoIn = pow( exoNdV, 2.5 ) * ( 0.75 + exoScanAmt ) * ( 0.8 + 0.4 * exoB.a );
      totalEmissiveRadiance += EXO_TEAL * exoIn * ( 0.03 + 0.2 * uEnergy + 0.5 * exoPulse( 0.5 ) );
    `,
  });

  // ---- thruster nozzle: heat-tinted metal ------------------------------------------------
  mat.thruster = D(P({ color: lin(0.3, 0.27, 0.245), metalness: 1, roughness: 0.4, anisotropy: 0.45 }), {
    key: 'thruster', A: GRAIN(14.0), B: WEAR(3.0, [0.65, 0.45, 0.25]),
    ...brushed({
      streak: 0.3, bump: 0.4, wave: 0.006, scratch: 0.6, edge: 0.5, smudge: 0.3,
      extraAlbedo: /* glsl */ `
        // temper colours: the oxide film thickens toward the hot zone — bare metal → straw →
        // bronze → purple → blue → pale blue. Banded along local Y (one hot zone every 24 cm),
        // warped by the wear cloud so the bands wander like real heat tint.
        float exoHeat = abs( fract( vExoPos.y * 4.2 + ( exoB.a - 0.5 ) * 0.16 + ( exoB.r - 0.5 ) * 0.05 ) * 2.0 - 1.0 );
        exoHeat = saturate( ( exoHeat - 0.3 ) / 0.7 );
        vec3 exoTemper = diffuseColor.rgb;
        exoTemper = mix( exoTemper, vec3( 0.74, 0.62, 0.38 ), smoothstep( 0.04, 0.26, exoHeat ) );
        exoTemper = mix( exoTemper, vec3( 0.56, 0.36, 0.24 ), smoothstep( 0.26, 0.42, exoHeat ) );
        exoTemper = mix( exoTemper, vec3( 0.38, 0.23, 0.42 ), smoothstep( 0.42, 0.56, exoHeat ) );
        exoTemper = mix( exoTemper, vec3( 0.17, 0.24, 0.52 ), smoothstep( 0.56, 0.72, exoHeat ) );
        exoTemper = mix( exoTemper, vec3( 0.36, 0.47, 0.6 ), smoothstep( 0.72, 0.92, exoHeat ) );
        diffuseColor.rgb = mix( diffuseColor.rgb, exoTemper, 0.7 );
        diffuseColor.rgb *= 1.0 - 0.3 * smoothstep( 0.6, 0.95, exoB.r );   // soot
      `,
      extraSurface: /* glsl */ `roughnessFactor += 0.25 * smoothstep( 0.55, 0.95, exoB.r );`,
    }),
    emissive: /* glsl */ `
      // the nozzle picks up the plume's light when the thrusters fire
      totalEmissiveRadiance += EXO_TEAL * uThrust * uThrust * 0.04 * ( 0.4 + 0.6 * exoB.a );
    `,
  });

  // ---- emitters ----------------------------------------------------------------------------
  const energy = (level, pulseGain) => ({
    A: FLAKE(30.0),
    B: { tex: tex.wear, scale: 6.5, off: [0.2, 0.9, 0.4], scroll: 'vec3( 0.0, - uTime * 0.11, 0.0 )' },
    emissive: /* glsl */ `
      float exoLvl = ${f(level)} * 2.72 * pow( max( uEnergy, 0.0 ), 1.53 );   // ≈ 0.5 idle … 2.8 at the hero beat
      float exoGlow = exoLvl * ( 0.88 + 0.24 * exoB.a ) * ( 0.7 + 0.3 * exoNdV );     // light-guide: slow drifting density
      exoGlow *= 0.9 + 0.7 * exoA.b * ( 1.0 - exoFadeA );                             // emitter cells, only resolved in macro
      exoGlow += ${f(pulseGain)} * exoPulse( 0.5 ) * ( 0.5 + uEnergy );
      // brighter = hotter: the line burns from saturated teal toward white-teal
      totalEmissiveRadiance = mix( EXO_TEAL, EXO_TEAL_HOT, saturate( ( exoGlow - 2.0 ) * 0.08 ) ) * exoGlow;
    `,
  });
  mat.energy = D(P({ color: lin(0.0, 0.05, 0.048), metalness: 0, roughness: 0.22 }), { key: 'energy', ...energy(1.0, 8.0) });
  mat.energyDim = D(P({ color: lin(0.0, 0.04, 0.038), metalness: 0, roughness: 0.3 }), { key: 'energyDim', ...energy(0.32, 2.4) });

  mat.core = D(P({ color: lin(0.0, 0.03, 0.03), metalness: 0, roughness: 0.12 }), {
    key: 'core', B: { tex: tex.wear, scale: 7.0, off: [0.0, 0.0, 0.0], scroll: 'vec3( 0.0, uTime * 0.33, uTime * 0.2 )' },
    emissive: /* glsl */ `
      // plasma: two cloud fields drifting through each other; filaments where they cross
      float exoT = uTime * 0.33;
      vec4 exoC2 = exoTri( uExoB, vExoPos * 12.5 - vec3( exoT * 0.8, exoT * 1.3, 0.0 ) + exoB.r * 0.3, exoW );
      float exoPl = smoothstep( 0.15, 0.7, exoB.r * 0.6 + exoC2.a * 0.6 - 0.1 );
      float exoFil = pow( 1.0 - abs( exoB.a - exoC2.r ), 24.0 );
      float exoHot = exoNdV * exoNdV;
      float exoBeat = 1.6 * uCore * uCore * ( 1.0 + 0.05 * sin( uTime * 11.0 ) + 0.035 * sin( uTime * 27.3 + 1.3 ) );
      // dim swirling body, HDR filaments, a hot heart where the view looks straight in
      vec3 exoPlasma = mix( vec3( 0.0, 0.3, 0.34 ), EXO_TEAL, exoPl ) * ( 0.07 + 0.42 * exoPl * exoPl )
                     + EXO_TEAL_HOT * ( 7.5 * exoFil * ( 0.15 + exoPl ) )
                     + mix( EXO_TEAL, EXO_TEAL_HOT, exoHot ) * ( 0.55 * pow( exoHot, 4.0 ) * ( 0.35 + exoPl ) );
      totalEmissiveRadiance = exoPlasma * exoBeat;
    `,
  });

  mat.thrusterGlow = D(P({ color: 0x000000, metalness: 0, roughness: 1, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide }), {
    key: 'thrusterGlow', B: { tex: tex.wear, scale: 5.0, off: [0.3, 0.0, 0.6], scroll: 'vec3( 0.0, uTime * 1.9, 0.0 )' },
    unlit: true,
    final: /* glsl */ `
      float exoF = pow( exoNdV, 2.2 );                                         // soft toward the silhouette
      float exoFlick = 0.9 + 0.1 * sin( uTime * 53.0 ) * sin( uTime * 31.7 + 2.0 );
      float exoA2 = uThrust * exoFlick;
      vec3 exoPlume = mix( EXO_TEAL, vec3( 0.72, 1.0, 0.95 ), exoF * exoF );
      // additive: all of the falloff lives in the colour, alpha stays 1
      outgoingLight = exoPlume * ( 0.3 + 6.5 * exoF * exoF ) * exoF * ( 0.55 + 0.8 * exoB.r ) * exoA2 * exoA2;
      diffuseColor.a = 1.0;
    `,
  });
  // A plume must never cast a shadow; main.js enables castShadow on every suit mesh by default.
  mat.thrusterGlow.onBeforeRender = (_r, _s, _c, _g, object) => { if (object.castShadow) object.castShadow = false; };

  // ---- per-frame ---------------------------------------------------------------------------
  // Measure the pulse sweeps once from the timeline itself (pure function of time), so that
  //  · the afterglow of a sweep is carried across the head → feet wrap only when a sweep really
  //    preceded it, and
  //  · when the last sweep leaves the top of the suit its trail keeps fading instead of popping
  //    off (the band is simply run on past 1.0, above the helmet).
  const sweep = { start: Infinity, firstWrap: Infinity, end: -Infinity, period: 0 };
  for (let i = 0, prev = -1; i <= DURATION * 240; i++) {
    const t = i / 240, p = computeState(t).pulse;
    if (p >= 0) {
      if (sweep.start === Infinity) sweep.start = t;
      if (prev >= 0 && p < prev && sweep.firstWrap === Infinity) sweep.firstWrap = t;
      sweep.end = t;
    }
    prev = p;
  }
  sweep.period = sweep.firstWrap < Infinity ? sweep.firstWrap - sweep.start : sweep.end - sweep.start;

  const all = Object.values(mat).filter((m) => m && m.isMaterial);
  let envScale = 1;
  mat.update = (state) => {
    let pulse = state.pulse, ghost = 0;
    if (pulse >= 0) ghost = state.t >= sweep.firstWrap - 1e-6 ? 1 : 0;
    else if (sweep.period > 0 && state.t > sweep.end && state.t < sweep.end + 2) pulse = 1 + (state.t - sweep.end) / sweep.period;
    uniforms.uTime.value = state.t;
    uniforms.uEnergy.value = state.energy;
    uniforms.uPulse.value = pulse;
    uniforms.uPulseGhost.value = ghost;
    uniforms.uCore.value = state.core;
    uniforms.uThrust.value = state.thrust;
    // look-dev aid: --force=env:0.5 scales image-based lighting (env:0 → direct lights only)
    if (state.env !== undefined && state.env !== envScale) {
      envScale = state.env;
      for (const m of all) if (m !== mat.thrusterGlow) m.onBeforeRender = (_r, scene) => { scene.environmentIntensity = envScale; };
    }
  };
  return mat;
}

// =====================================================================================
// Material test bench  (?demo=mat  /  node tools/shot.mjs --demo=mat)
// =====================================================================================
// A two-tier rack standing on the platform, facing +Z. One column per material; in every column
// the cylinder is at the bottom, the bevelled box (3 mm bevels) in the middle and the sphere on
// top. Columns run left → right as seen from the front (+Z) camera, i.e. from the suit's right
// (−X) to its left (+X), in the order below; both tiers start at the same left edge. When the
// geometry kit is present, the free right-hand end of the lower shelf holds a small armour study
// (titanium plates over a cobalt sleeve and frame core, a cobalt dome, a flat titanium plate).
// Emitters follow the film clock: use --force=energy:1,core:1.2,thrust:1,pulse:0.3 to drive them.
export const DEMO_ORDER = [
  // upper tier
  ['titanium', 'titaniumDark', 'cobalt', 'cobaltDark', 'frame', 'steel', 'copper', 'copperCoil', 'thruster', 'carbon'],
  // lower tier
  ['rubber', 'wire', 'wireTeal', 'glass', 'energy', 'energyDim', 'core', 'thrusterGlow'],
];

export function buildDemo({ mat, kit }) {
  const group = new THREE.Group();
  group.name = 'materials-demo';
  const pitch = 0.36;
  const cyl = new THREE.CylinderGeometry(0.085, 0.085, 0.24, 48, 1);
  const cube = new RoundedBoxGeometry(0.19, 0.19, 0.19, 4, 0.003);
  const ball = new THREE.SphereGeometry(0.1, 48, 32);
  const shelfMat = mat.frame;
  DEMO_ORDER.forEach((row, tier) => {
    const y0 = tier === 0 ? 1.04 : 0.1;
    const w = DEMO_ORDER[0].length * pitch + 0.08;
    const shelf = new THREE.Mesh(new RoundedBoxGeometry(w, 0.03, 0.3, 2, 0.004), shelfMat);
    shelf.position.set(0, y0 - 0.015, 0);
    group.add(shelf);
    row.forEach((name, i) => {
      const m = mat[name];
      if (!m) return;
      const x = (i - (DEMO_ORDER[0].length - 1) / 2) * pitch; // both tiers start at the same left edge
      const add = (geo, y, ry = 0) => {
        const mesh = new THREE.Mesh(geo, m);
        mesh.position.set(x, y0 + y, 0);
        mesh.rotation.y = ry;
        mesh.userData.castShadow = !m.transparent;
        group.add(mesh);
      };
      add(cyl, 0.12);
      add(cube, 0.24 + 0.03 + 0.095, 0.5);
      add(ball, 0.24 + 0.03 + 0.19 + 0.03 + 0.1);
    });
  });
  // If the geometry kit is available, add a small armour study built from real kit shells (a
  // forearm-like stack and a pauldron dome) at the right-hand end of the lower shelf, so the
  // metals can be judged on bevelled, curved plates instead of primitives.
  try {
    if (kit && kit.tubeShell && kit.domeShell && kit.plate) {
      const study = new THREE.Group();
      study.position.set(1.5, 0.1, 0.02);
      const put = (geo, m, x, y, z, ry = 0, rx = 0) => {
        const mesh = new THREE.Mesh(geo, m);
        mesh.position.set(x, y, z);
        mesh.rotation.set(rx, ry, 0);
        study.add(mesh);
        return mesh;
      };
      // limb: frame core, cobalt sleeve, titanium plates wrapped over it (kit tubes run along Y)
      put(new THREE.CylinderGeometry(0.046, 0.042, 0.44, 32), mat.frame, -0.13, 0.24, 0);
      put(kit.tubeShell({ r0: 0.06, r1: 0.052, y0: 0, y1: 0.17, t: 0.006 }), mat.cobalt, -0.13, 0.03, 0);
      put(kit.tubeShell({ r0: 0.076, r1: 0.064, y0: 0, y1: 0.24, arc: [-1.15, 1.15], t: 0.006, cut: [0.02, 0.02, 0, 0] }), mat.titanium, -0.13, 0.19, 0, 0.3);
      put(kit.tubeShell({ r0: 0.073, r1: 0.062, y0: 0, y1: 0.2, arc: [2.0, 4.1], t: 0.005 }), mat.titaniumDark, -0.13, 0.21, 0, 0.3);
      // pauldron dome over a flat clipped plate
      put(kit.domeShell({ r: 0.1, spanX: 1.5, spanY: 1.3, t: 0.006 }), mat.cobalt, 0.13, 0.5, -0.06, -0.25, -0.3);
      put(kit.plate({ w: 0.19, h: 0.27, t: 0.006, cut: [0.03, 0, 0.03, 0] }), mat.titanium, 0.14, 0.17, 0.0, -0.3, -0.1);
      group.add(study);
    }
  } catch (err) {
    /* kit API differs from what this study expects — the bench is complete without it */
  }
  // two uprights so the rack reads as one object standing on the platform
  for (const sx of [-1, 1]) {
    const post = new THREE.Mesh(new RoundedBoxGeometry(0.04, 1.06, 0.04, 2, 0.004), shelfMat);
    post.position.set(sx * (DEMO_ORDER[0].length * pitch * 0.5 + 0.02), 0.53, -0.12);
    group.add(post);
  }
  return group;
}
