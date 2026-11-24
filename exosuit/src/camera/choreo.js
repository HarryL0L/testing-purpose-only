// Camera choreography — the film's cinematography. Contract (unchanged from the placeholder):
//   createCameraRig(ctx) → { camera, update(state, frame) → shot }
//   shot = { focus: metres to the plane of sharp focus (measured along the view axis),
//            blur: 0..1 depth-of-field strength (0 = pinhole, 1 = widest aperture),
//            cut: true on the first frame after a hard cut,
//            name: id of the current shot (debug) }
//   frame = { suit, env, aspect, renderer, scene } ; suit.anchor(name, vec3) gives world focus points.
//
// The camera is a PURE FUNCTION of `state` (no filters, no memory): every frame the pose is
// derived analytically from state.t, the live focus anchors and tables measured once at start-up.
//
// SHOT LIST (timings come from core/timeline.js)
//   open     0.00 →  6.20  one continuous move: low push-in + crane on the turntable, which
//                          opens into a pull-back orbit as the suit explodes.
//   macros   6.20 → 14.95  seven long-lens close-ups, 1.25 s each, HARD CUTS between them, each
//                          with a slow continuous arc / push (never a locked-off frame).
//   finale  14.95 → 20.00  hard cut to a high wide, then one continuous descending arc that
//                          catches the reassembly and lands in the low-angle hero push-in.
//
// FOCUS PULLING. Rig anchors sit on the joints, but during the showcase the suit is exploded and
// the mechanism that articulates usually rides on a panel floating 0.2–0.4 m away from its joint.
// At start-up `probeMechanisms()` therefore poses the suit with each section closed / open, finds
// the meshes that actually move, and stores where their (anchor-weighted) centroid sits relative
// to the section's anchor. Macro shots aim at, and focus on, that point. When nothing moves
// (envelope proxies, or a region that is not built yet) the shot falls back to the mean explode
// offset of the panels around the anchor, so it still frames whatever floats there.
import * as THREE from 'three';
import * as TL from '../core/timeline.js';

const DEG = Math.PI / 180;

// ---- tunables ---------------------------------------------------------------------------
const NEAR = 0.05;
const FAR = 300;
const WIDE_FOV = 28; // vertical FOV (deg) of the wide shots — a longish "product" lens

// Wide moves are keyframed scalar tracks: [time, value, (optional) explicit slope].
//   az     degrees around the suit axis, world space (0 = +Z = in front of the detent pose,
//          positive = toward +X)
//   camY   camera height (m)
//   halfH  half the frame height at the subject (m) — the camera distance is derived from this,
//          the lens and the aspect ratio, so framing survives any aspect from 1:1 to 2.4:1
//   tgtY   height of the look-at point on the suit axis (m)
const OPEN = {
  az: [[0, -17], [2, -7], [4, 5], [5.2, 21], [6.2, 31]],
  camY: [[0, 0.8], [2, 1.0], [4, 1.24], [5.2, 1.52], [6.2, 1.64]],
  halfH: [[0, 1.54], [2, 1.41], [3.7, 1.33], [5.0, 1.66], [5.8, 1.8], [6.2, 1.84]],
  tgtY: [[0, 1.0], [4, 1.02], [5.2, 1.15], [6.2, 1.2]],
  halfW: 1.5, // half-width that must stay in frame (exploded cloud + margin)
  blur: [[0, 0.5], [4, 0.5], [6.2, 0.6]],
};
const FINALE = {
  az: [[14.95, -35], [16.6, -13], [18.2, -3.5], [20, 0, 0]],
  camY: [[14.95, 1.58], [16.6, 0.96], [18.2, 0.62], [20, 0.5, 0]],
  halfH: [[14.95, 1.86], [15.7, 1.74], [16.6, 1.52], [18.2, 1.36], [20, 1.27, 0]],
  tgtY: [[14.95, 1.2], [16.6, 1.09], [20, 1.05, 0]],
  halfW: 1.5,
  blur: [[14.95, 0.55], [16.6, 0.6], [20, 0.75, 0]],
};

// Macro close-ups. Angles are in SUIT space (az 0 = in front of the suit, 90 = its left,
// 180 = behind; el > 0 looks down on the subject). [start, end] pairs drift linearly over the
// shot. `dist` is metres from the look-at point, `fov` the vertical lens angle at 16:9.
//   glide: [anchorA, anchorB, [m0, m1]]  the look-at travels between two anchors during the shot
const MACROS = {
  helmet: { az: [31, 15], el: [3, -2], dist: [0.98, 0.9], fov: 24, blur: 0.62 },
  shoulders: { az: [40, 57], el: [15, 9], dist: [0.96, 0.9], fov: 24, blur: 0.62 },
  chest: { az: [-21, -9], el: [5, 1], dist: [1.0, 0.86], fov: 26, blur: 0.6 },
  forearms: { az: [61, 80], el: [11, 6], dist: [0.9, 0.84], fov: 22, blur: 0.62 },
  fingers: { az: [30, 13], el: [17, 10], dist: [0.68, 0.61], fov: 22, blur: 0.58 },
  back: { az: [156, 173], el: [12, 5], dist: [1.0, 0.94], fov: 26, blur: 0.6 },
  legs: { az: [39, 25], el: [7, 2], dist: [1.0, 0.95], fov: 26, blur: 0.6, glide: ['knee', 'boot', [0.28, 0.72]] },
};
const MACRO_FOLLOW = 0.8; // how much of a moving anchor's travel the operator re-aims for
const MACRO_NEED_W = 1.3; // half-width (× design half-height) that must stay framed on narrow aspects
const MECH_AIM = 0.8; // aim this far from the joint anchor toward the detected moving mechanism
const MECH_MAX = 0.55; // never chase a mechanism further than this from its anchor (m)

// Reassembly impact: every panel that locks home nudges the camera by a hair; the last lock
// gets a slightly bigger settle. Closed-form in t (sum of damped sines), so it scrubs.
const KICK = { panel: 0.0016, final: 0.008, decay: 0.075, freq: 58, fovPunch: 0.012 };

// ---- helpers ----------------------------------------------------------------------------
/** C1 scalar keyframe track (Catmull-Rom tangents; a key's 3rd entry overrides its slope). */
function track(keys) {
  const n = keys.length;
  const m = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    if (keys[i][2] !== undefined) { m[i] = keys[i][2]; continue; }
    const a = keys[Math.max(0, i - 1)], b = keys[Math.min(n - 1, i + 1)];
    m[i] = (b[1] - a[1]) / (b[0] - a[0]);
  }
  return (t) => {
    if (t <= keys[0][0]) return keys[0][1];
    if (t >= keys[n - 1][0]) return keys[n - 1][1];
    let i = 0;
    while (t > keys[i + 1][0]) i++;
    const t0 = keys[i][0], h = keys[i + 1][0] - t0, u = (t - t0) / h;
    const u2 = u * u, u3 = u2 * u;
    return (2 * u3 - 3 * u2 + 1) * keys[i][1] + (u3 - 2 * u2 + u) * h * m[i]
      + (-2 * u3 + 3 * u2) * keys[i + 1][1] + (u3 - u2) * h * m[i + 1];
  };
}
const tracks = (def) => ({
  az: track(def.az), camY: track(def.camY), halfH: track(def.halfH), tgtY: track(def.tgtY), blur: track(def.blur), halfW: def.halfW,
});

export function createCameraRig(ctx) {
  const suit = ctx.suit;
  const camera = new THREE.PerspectiveCamera(WIDE_FOV, 16 / 9, NEAR, FAR);
  const open = tracks(OPEN);
  const finale = tracks(FINALE);

  // scratch (no per-frame allocation)
  const pos = new THREE.Vector3();
  const tgt = new THREE.Vector3();
  const foc = new THREE.Vector3();
  const va = new THREE.Vector3();
  const vb = new THREE.Vector3();
  const vc = new THREE.Vector3();
  const dir = new THREE.Vector3();
  const off = new THREE.Vector3();
  const qRoot = new THREE.Quaternion();
  const shot = { focus: 5, blur: 0, cut: false, name: 'open' };

  // ---- start-up measurements -----------------------------------------------------------
  // per section: ref (anchor in suit space while the section holds its pose), near (panels
  // around the anchor, for the fallback offset), mech (detected mechanism offsets)
  const sect = {};
  for (const name of TL.SECTIONS) sect[name] = { ref: null, near: [], mech: null };
  let impacts = [];
  let tLock = TL.PHASES.reassemble[1];
  try {
    measure();
  } catch (err) {
    console.warn('[camera] start-up probe failed, using plain anchors: ' + (err && err.message));
  }

  function measure() {
    const root = suit.root;
    const inv = new THREE.Matrix4();
    const m = new THREE.Matrix4();
    const box = new THREE.Box3();
    const v = new THREE.Vector3();
    const toLocal = () => { root.updateMatrixWorld(true); inv.copy(root.matrixWorld).invert(); };

    // -- panels around each anchor (rest pose) → fallback explode offset
    suit.update(TL.computeState(0));
    toLocal();
    const pc = suit.panels.map((p) => {
      box.setFromObject(p.obj);
      return (box.isEmpty() ? p.obj.getWorldPosition(new THREE.Vector3()) : box.getCenter(new THREE.Vector3())).applyMatrix4(inv);
    });
    for (const name of TL.SECTIONS) {
      suit.anchor(name, v).applyMatrix4(inv);
      suit.panels.forEach((p, i) => {
        const w = Math.exp(-pc[i].distanceToSquared(v) / (0.2 * 0.2));
        if (w > 0.05) sect[name].near.push({ p, w });
      });
    }

    // -- moving-mesh probe
    const meshes = [];
    root.traverse((o) => { if (o.isMesh && o.geometry && o.geometry.attributes && o.geometry.attributes.position) meshes.push(o); });
    const n = meshes.length;
    const lo = new Float32Array(n * 3), hi = new Float32Array(n * 3), size = new Float32Array(n);
    meshes.forEach((o, i) => {
      const g = o.geometry;
      if (!g.boundingBox) g.computeBoundingBox();
      g.boundingBox.min.toArray(lo, i * 3);
      g.boundingBox.max.toArray(hi, i * 3);
    });
    const A = new Float32Array(n * 6), B = new Float32Array(n * 6);
    const sample = (out) => {
      toLocal();
      for (let i = 0; i < n; i++) {
        m.multiplyMatrices(inv, meshes[i].matrixWorld);
        v.fromArray(lo, i * 3).applyMatrix4(m).toArray(out, i * 6);
        v.fromArray(hi, i * 3).applyMatrix4(m).toArray(out, i * 6 + 3);
      }
    };
    const anchorLocal = new THREE.Vector3();
    const SIGMA2 = 0.38 * 0.38;
    // centroid (relative to the section anchor) of everything the section moves at film time t
    const probe = (name, t, forceExplode, acc) => {
      const s1 = TL.computeState(t);
      const s0 = TL.computeState(t);
      s0.section[name] = 0;
      s0.active = null;
      if (forceExplode !== undefined) s0.forceExplode = s1.forceExplode = forceExplode;
      suit.update(s0); sample(A);
      suit.update(s1); sample(B);
      suit.anchor(name, anchorLocal).applyMatrix4(inv);
      for (let i = 0; i < n; i++) {
        const k = i * 6;
        const d0 = Math.hypot(B[k] - A[k], B[k + 1] - A[k + 1], B[k + 2] - A[k + 2]);
        const d1 = Math.hypot(B[k + 3] - A[k + 3], B[k + 4] - A[k + 4], B[k + 5] - A[k + 5]);
        const d = Math.max(d0, d1);
        if (!(d > 0.006)) continue;
        const cx = (B[k] + B[k + 3]) / 2 - anchorLocal.x, cy = (B[k + 1] + B[k + 4]) / 2 - anchorLocal.y, cz = (B[k + 2] + B[k + 5]) / 2 - anchorLocal.z;
        const sz = Math.hypot(B[k + 3] - B[k], B[k + 4] - B[k + 1], B[k + 5] - B[k + 2]);
        const w = Math.exp(-(cx * cx + cy * cy + cz * cz) / SIGMA2) * TL.clamp(sz, 0.02, 0.25) * Math.min(d, 0.08) / 0.08;
        acc.w += w; acc.x += w * cx; acc.y += w * cy; acc.z += w * cz;
      }
      return anchorLocal;
    };
    const finish = (acc) => {
      if (acc.w < 0.002) return null;
      const o = new THREE.Vector3(acc.x / acc.w, acc.y / acc.w, acc.z / acc.w);
      if (o.length() > MECH_MAX) o.setLength(MECH_MAX);
      return o;
    };
    for (const name of TL.SECTIONS) {
      const [a, b] = TL.SECTION_TIME[name];
      const acc1 = { w: 0, x: 0, y: 0, z: 0 }, acc0 = { w: 0, x: 0, y: 0, z: 0 };
      probe(name, TL.lerp(a, b, 0.3), undefined, acc1);
      probe(name, TL.lerp(a, b, 0.7), undefined, acc1);
      probe(name, TL.lerp(a, b, 0.5), 0, acc0); // assembled: only matters for debug-forced states
      sect[name].ref = probe(name, TL.lerp(a, b, 0.5), undefined, acc1).clone();
      const o1 = finish(acc1);
      if (o1) sect[name].mech = { exploded: o1, assembled: finish(acc0) || o1.clone().multiplyScalar(0.3), weight: acc1.w };
    }

    // -- reassembly impacts
    const snaps = suit.snaps || [];
    if (snaps.length) {
      tLock = snaps[snaps.length - 1].t;
      const span = Math.max(0.2, tLock - snaps[0].t);
      const overlap = Math.max(1, (snaps.length / span) * KICK.decay * 2.5); // kicks ringing at once
      impacts = snaps.map((s, i) => ({ t: s.t, a: (KICK.panel / Math.sqrt(overlap)) * (0.6 + 0.8 * TL.hash(i * 5.3 + 1.7)) }));
    }
    suit.update(TL.computeState(0));
  }

  /** Sum of damped impact wobbles at time t (metres, signed, ≈ mm). */
  function impactKick(t) {
    let k = 0;
    for (let i = 0; i < impacts.length; i++) {
      const tau = t - impacts[i].t;
      if (tau <= 0) break;
      if (tau > KICK.decay * 7) continue;
      k += impacts[i].a * Math.exp(-tau / KICK.decay) * Math.sin(tau * KICK.freq);
    }
    const tau = t - tLock;
    if (tau > 0 && tau < 1.2) k += KICK.final * Math.exp(-tau / (KICK.decay * 1.5)) * Math.sin(tau * KICK.freq * 0.45);
    return k;
  }

  // ---- shots ----------------------------------------------------------------------------
  /** Wide orbit shot from scalar tracks. */
  function wide(tr, t, aspect) {
    const th = Math.tan((WIDE_FOV * DEG) / 2);
    const halfH = tr.halfH(t);
    const dist = Math.max(halfH / th, tr.halfW / (th * aspect));
    const az = tr.az(t) * DEG, camY = tr.camY(t), tgtY = tr.tgtY(t);
    const r = Math.sqrt(Math.max(2.6, dist * dist - (camY - tgtY) * (camY - tgtY)));
    pos.set(Math.sin(az) * r, camY, Math.cos(az) * r);
    tgt.set(0, tgtY, 0);
    foc.copy(tgt);
    camera.fov = WIDE_FOV;
    shot.blur = tr.blur(t);
  }

  /** Explode offset (suit space) of whatever floats around a section's anchor → `off`; returns mean amount. */
  function nearOffset(s) {
    let sw = 0, sm = 0, se = 0;
    off.set(0, 0, 0);
    for (let i = 0; i < s.near.length; i++) {
      const { p, w } = s.near[i];
      const e = p.amount || 0;
      sw += w;
      if (p.dist > 0.001) { sm += w; se += w * e; off.addScaledVector(p.dir, p.dist * e * w); }
    }
    if (sw > 0) off.multiplyScalar(1 / sw);
    return sm > 0 ? se / sm : 0;
  }

  function macro(name, u, state, aspect) {
    const def = MACROS[name], s = sect[name];
    const root = suit.root;
    root.getWorldQuaternion(qRoot);

    // live subject point (world)
    if (def.glide) {
      suit.anchor(def.glide[0], va);
      suit.anchor(def.glide[1], vb);
      va.lerp(vb, TL.lerp(def.glide[2][0], def.glide[2][1], u));
    } else suit.anchor(name, va);
    // reference point the camera body is anchored to: the anchor in its held pose, so the camera
    // does not ride along when the limb moves (it re-aims instead). Outside the real section
    // window (debug --force) or without a probe, ride the live anchor.
    const [ta, tb] = TL.SECTION_TIME[name];
    const inWindow = state.t >= ta - 1e-6 && state.t <= tb + 1e-6;
    if (inWindow && s.ref && !def.glide) vb.copy(s.ref).applyMatrix4(root.matrixWorld);
    else vb.copy(va);

    // where the mechanism actually is, relative to the anchor (suit space → world)
    const e = nearOffset(s); // → off (fallback), e = how exploded the neighbourhood is
    let aim = 1;
    if (s.mech) { off.lerpVectors(s.mech.assembled, s.mech.exploded, TL.clamp01(e)); aim = MECH_AIM; }
    off.applyQuaternion(qRoot);

    foc.copy(va).add(off); // exact focus point
    tgt.copy(vb).lerp(va, MACRO_FOLLOW).addScaledVector(off, aim);
    const az = TL.lerp(def.az[0], def.az[1], u) * DEG, el = TL.lerp(def.el[0], def.el[1], u) * DEG;
    dir.set(Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)).applyQuaternion(qRoot);
    pos.copy(vb).addScaledVector(off, aim).addScaledVector(dir, TL.lerp(def.dist[0], def.dist[1], u));
    if (pos.y < 0.12) pos.y = 0.12; // never dip into the platform

    // narrow frames: open the lens just enough to keep the designed width in frame
    const th = Math.tan((def.fov * DEG) / 2);
    camera.fov = (2 * Math.atan(th * Math.max(1, MACRO_NEED_W / aspect))) / DEG;
    shot.blur = def.blur;
  }

  function update(state, frame) {
    const t = state.t;
    const aspect = frame.aspect || 16 / 9;
    camera.aspect = aspect;
    let kick = 0;

    if (state.active && MACROS[state.active]) {
      const u = TL.clamp01(state.section[state.active]);
      macro(state.active, u, state, aspect);
      shot.name = state.active;
      shot.cut = u * TL.SECTION_LEN < 1 / TL.FPS - 1e-4;
    } else if (t < TL.PHASES.showcase[0]) {
      wide(open, t, aspect);
      shot.name = 'open';
      shot.cut = t < 1 / TL.FPS - 1e-4;
    } else {
      const t0 = TL.PHASES.reassemble[0];
      wide(finale, Math.max(t, t0), aspect);
      kick = impactKick(t);
      shot.name = 'finale';
      shot.cut = t - t0 < 1 / TL.FPS - 1e-4;
    }

    camera.position.copy(pos);
    camera.up.set(0, 1, 0);
    camera.lookAt(tgt);
    if (kick !== 0) {
      // settle: a hair of drop + dolly, and a matching lens "punch"
      camera.getWorldDirection(vc);
      camera.position.y -= kick;
      camera.position.addScaledVector(vc, kick * 1.5);
      camera.lookAt(tgt.x, tgt.y + kick * 0.35, tgt.z);
      camera.fov *= 1 - (kick / KICK.final) * KICK.fovPunch * 0.5;
    }
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld(true);

    // focus distance = depth of the focus point along the view axis
    camera.getWorldDirection(vc);
    shot.focus = Math.max(0.2, vc.dot(foc.sub(camera.position)));
    return shot;
  }

  return { camera, update, /** debug: measured section data */ sections: sect };
}
