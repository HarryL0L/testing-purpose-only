// Suit assembler: builds the rig, runs every region builder, and owns the explode /
// reassemble motion of all armour panels. Region modules never move their panel carriers —
// they only animate what is inside them.
import * as THREE from 'three';
import { createRig, DIMS } from './rig.js';
import * as kit from './kit.js';
import * as TL from '../core/timeline.js';
import buildTorso from './torso.js';
import buildBack from './back.js';
import buildHead from './head.js';
import buildArms from './arms.js';
import buildHands from './hands.js';
import buildLegs from './legs.js';

const BUILDERS = [
  ['torso', buildTorso],
  ['back', buildBack],
  ['head', buildHead],
  ['arms', buildArms],
  ['hands', buildHands],
  ['legs', buildLegs],
];
const SIDES = [['L', 1], ['R', -1]];

export function buildSuit({ mat }) {
  const rig = createRig();
  const panels = [];
  const regions = [];

  for (const [name, build] of BUILDERS) {
    const seen = new Set();
    for (const j of Object.values(rig.joints)) for (const c of j.children) seen.add(c);
    const ctx = {
      THREE, TL, rig, J: rig.joints, D: DIMS, mat, kit, name, sides: SIDES,
      /**
       * Create an armour-panel carrier under `joint` and register it for the exploded view.
       * Add your meshes / hinge groups INSIDE the returned Group. You may set its position /
       * rotation once at build time (that becomes its rest transform); after that the director
       * owns the carrier's transform — animate children only.
       * @param {THREE.Object3D} joint  a rig joint (ctx.J.*) — or any object that never animates
       * @param {{name?:string, dir?:number[], dist?:number, delay?:number, spin?:number[], order?:number}} opts
       *   dir   suit-space direction the panel floats in the exploded view (need not be unit length)
       *   dist  metres travelled at full explode (0 = stays put, e.g. inner frame carriers)
       *   delay 0..1 stagger within the explode phase (0 leaves first)
       *   spin  [rx,ry,rz] small extra rotation (radians) at full explode
       *   order 0..1 optional override of the reassembly order (0 snaps back first)
       */
      panel(joint, opts = {}) {
        const g = new THREE.Group();
        g.name = 'panel:' + (opts.name || name + panels.length);
        joint.add(g);
        panels.push({
          obj: g, region: name, name: opts.name || g.name,
          dir: new THREE.Vector3().fromArray(opts.dir || [0, 0, 1]).normalize(),
          dist: opts.dist ?? 0.3, delay: opts.delay ?? TL.hash(panels.length * 3.7),
          spin: opts.spin || null, order: opts.order,
        });
        return g;
      },
    };
    let region = null;
    try {
      region = build(ctx) || {};
    } catch (err) {
      console.error(`[suit] region "${name}" failed to build`, err);
      region = {};
    }
    for (const j of Object.values(rig.joints)) {
      for (const c of j.children) if (!seen.has(c) && !c.name.startsWith('joint:') && !c.name.startsWith('anchor:')) c.userData.region = name;
    }
    regions.push({ name, update: region.update || null });
  }

  // ---- rest transforms, reassembly order -------------------------------------------
  rig.setPose({});
  const v = new THREE.Vector3();
  const box = new THREE.Box3();
  for (const [i, p] of panels.entries()) {
    p.restPos = p.obj.position.clone();
    p.restQuat = p.obj.quaternion.clone();
    box.setFromObject(p.obj);
    const y = box.isEmpty() ? p.obj.getWorldPosition(v).y : (box.min.y + box.max.y) / 2;
    p.centerY = y;
    // feet first, helmet last, with a little shuffle so neighbours don't land on the same frame
    if (p.order === undefined) p.order = TL.clamp01(y / DIMS.height) * 0.9 + TL.hash(i * 1.31) * 0.1;
    p.phase = TL.hash(i * 7.77) * Math.PI * 2;
    p.spinQuat = p.spin ? new THREE.Quaternion().setFromEuler(new THREE.Euler(...p.spin)) : null;
  }

  const R_LEN = TL.PHASES.reassemble[1] - TL.PHASES.reassemble[0];
  const R_TRAVEL = 0.34; // fraction of the reassemble phase one panel needs to fly home
  const snapU = (p) => p.order * (1 - R_TRAVEL) + R_TRAVEL; // phase progress at which it seats
  /** When (seconds) and roughly where each moving panel locks home — drives sparks & flashes. */
  const snaps = panels.filter((p) => p.dist > 0.001).map((p) => {
    const pos = box.setFromObject(p.obj).isEmpty() ? p.obj.getWorldPosition(new THREE.Vector3()) : box.getCenter(new THREE.Vector3());
    return { t: TL.PHASES.reassemble[0] + snapU(p) * R_LEN, pos, dir: p.dir.clone(), name: p.name, region: p.region };
  }).sort((a, b) => a.t - b.t);

  // ---- per-frame --------------------------------------------------------------------
  const qRoot = new THREE.Quaternion();
  const qPar = new THREE.Quaternion();
  const qId = new THREE.Quaternion();
  const off = new THREE.Vector3();

  function panelAmount(p, state) {
    if (state.forceExplode !== undefined) return state.forceExplode; // debug: ?force=explode:1
    if (state.t < TL.PHASES.reassemble[0]) {
      // unlatch pop, then a weighty glide outward
      const u = TL.clamp01((state.explodeU - p.delay * 0.42) / 0.58);
      if (u <= 0) return 0;
      return 0.035 * TL.smoothstep(TL.clamp01(u / 0.07)) + 0.965 * TL.easeInOutCubic(u);
    }
    // magnetic snap home: accelerate in, clack, tiny damped rebound
    const su = snapU(p);
    const r = TL.clamp01((state.reassembleU - (su - R_TRAVEL)) / R_TRAVEL);
    if (r < 1) return 1 - TL.easeInQuart(r) * 0.985 - 0.015 * r;
    const tau = (state.reassembleU - su) * R_LEN; // seconds since seating
    if (tau > 0.25) return 0;
    return 0.03 * Math.exp(-tau / 0.035) * Math.abs(Math.sin(tau * 58));
  }

  function update(state) {
    rig.setPose(state);
    for (const r of regions) if (r.update) r.update(state);
    rig.root.getWorldQuaternion(qRoot);
    for (const p of panels) {
      const e = p.dist > 0 || p.spinQuat ? panelAmount(p, state) : 0;
      p.amount = e;
      if (e <= 0) {
        p.obj.position.copy(p.restPos);
        p.obj.quaternion.copy(p.restQuat);
        continue;
      }
      // suit-space direction → the carrier's parent space (correct under any pose)
      p.obj.parent.getWorldQuaternion(qPar).invert().multiply(qRoot);
      off.copy(p.dir).multiplyScalar(p.dist * e);
      // exploded panels hover: a slow, tiny bob so the frozen diagram still feels alive
      const hover = TL.smoothstep(TL.clamp01((e - 0.6) / 0.4)) * 0.006;
      off.x += Math.sin(state.t * 1.1 + p.phase) * hover;
      off.y += Math.sin(state.t * 0.9 + p.phase * 1.7) * hover;
      off.z += Math.cos(state.t * 1.0 + p.phase * 0.6) * hover;
      off.applyQuaternion(qPar);
      p.obj.position.copy(p.restPos).add(off);
      if (p.spinQuat) p.obj.quaternion.copy(p.restQuat).multiply(qId.identity().slerp(p.spinQuat, e));
      else p.obj.quaternion.copy(p.restQuat);
    }
  }

  /** Show only the listed regions (debug: ?solo=head,arms). */
  function solo(names) {
    for (const j of Object.values(rig.joints)) {
      for (const c of j.children) if (c.userData.region) c.visible = names.includes(c.userData.region);
    }
  }

  return { root: rig.root, rig, panels, regions, snaps, update, solo, anchor: rig.anchor };
}
