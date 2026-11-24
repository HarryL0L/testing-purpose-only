// Proxy regions: grey mannequin volumes that follow the envelope spec in rig.js (DIMS).
// The dev build swaps any region you are not working on for its proxy (see tools/build.mjs
// --only), so every builder can line their geometry up against its neighbours.
import * as THREE from 'three';

const capsule = (r, len) => {
  const g = new THREE.CapsuleGeometry(r, Math.max(0.001, len - 2 * r), 6, 14);
  g.translate(0, -len / 2, 0);
  return g;
};

export function proxyRegion(name, ctx) {
  const { J, D, sides } = ctx;
  // proxies wear the real armour materials so look-dev can judge them on a body-sized form
  const pick = (n) => (/chest|pelvis|pauldron|shin|backpack|hand/.test(n) ? ctx.mat.cobalt : /neck|abdomen|upperArm/.test(n) ? ctx.mat.frame : ctx.mat.titanium);
  const add = (joint, geo, pos, opts) => {
    const mesh = new THREE.Mesh(geo, pick(opts.name));
    if (pos) mesh.position.fromArray(pos);
    const p = ctx.panel(joint, opts);
    p.add(mesh);
    return mesh;
  };
  const box = (w, h, d) => new THREE.BoxGeometry(w, h, d);

  if (name === 'head') {
    const h = D.head_;
    const g = new THREE.SphereGeometry(1, 20, 14);
    g.scale(h.halfW, h.topY / 2 + 0.01, h.halfD);
    add(J.head, g, [0, h.centerY, 0.005], { name: 'helmet', dir: [0, 1, 0.2], dist: 0.3 });
    add(J.neck, new THREE.CylinderGeometry(h.neckR, h.neckR, 0.1, 14), [0, 0.04, 0], { name: 'neck', dir: [0, 0, -1], dist: 0.0 });
  } else if (name === 'torso') {
    const c = D.chest_, a = D.abdomen_, p = D.pelvis_;
    add(J.chest, box(c.halfW * 2, c.topY - c.botY, c.frontZ - c.backZ), [0, (c.topY + c.botY) / 2, (c.frontZ + c.backZ) / 2], { name: 'chest', dir: [0, 0, 1], dist: 0.35 });
    add(J.spine, box(a.halfW * 2, a.topY - a.botY, a.halfD * 2), [0, (a.topY + a.botY) / 2, 0], { name: 'abdomen', dir: [0, 0, 1], dist: 0.25 });
    add(J.pelvis, box(p.halfW * 2, p.topY - p.botY, p.halfD * 2), [0, (p.topY + p.botY) / 2, 0], { name: 'pelvis', dir: [0, -0.2, 1], dist: 0.25 });
  } else if (name === 'back') {
    const b = D.back_;
    add(J.chest, box(b.halfW * 2, b.topY - b.botY, b.frontZ - b.backZ), [0, (b.topY + b.botY) / 2, (b.frontZ + b.backZ) / 2], { name: 'backpack', dir: [0, 0, -1], dist: 0.4 });
  } else if (name === 'arms') {
    for (const [S, s] of sides) {
      add(J['shoulder' + S], new THREE.SphereGeometry(D.shoulder_.pauldronR, 14, 10), [0.01 * s, 0.01, 0], { name: 'pauldron' + S, dir: [s, 0.5, 0], dist: 0.3 });
      add(J['shoulder' + S], capsule(D.upperArm_.r, D.upperArm_.len), null, { name: 'upperArm' + S, dir: [s, 0, 0], dist: 0.25 });
      add(J['elbow' + S], capsule(D.forearm_.armourR, D.forearm_.len), null, { name: 'forearm' + S, dir: [s, 0, 0.3], dist: 0.3 });
    }
  } else if (name === 'hands') {
    const h = D.hand_;
    for (const [S, s] of sides) {
      add(J['wrist' + S], box(h.palmT, h.len, h.palmW), [0, -h.len / 2 - 0.01, 0], { name: 'hand' + S, dir: [s, -0.4, 0], dist: 0.2 });
    }
  } else if (name === 'legs') {
    const b = D.boot_;
    for (const [S, s] of sides) {
      add(J['hip' + S], capsule(D.thigh_.rTop, D.thigh_.len), null, { name: 'thigh' + S, dir: [s, 0, 0.4], dist: 0.3 });
      add(J['knee' + S], capsule(D.shin_.rTop, D.shin_.len), null, { name: 'shin' + S, dir: [s, 0, 0.5], dist: 0.3 });
      add(J['ankle' + S], box(b.halfW * 2, 0.11, b.toeZ - b.heelZ), [0, b.soleY + 0.055, (b.toeZ + b.heelZ) / 2], { name: 'boot' + S, dir: [s * 0.6, 0, 1], dist: 0.3 });
    }
  }
  return {};
}
