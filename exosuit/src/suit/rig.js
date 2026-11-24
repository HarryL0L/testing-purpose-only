// Rig: the joint hierarchy every suit region hangs its geometry on, plus the poses the
// director blends between. Suit space: +Y up, the suit FACES +Z, so +X is the suit's LEFT.
// Side sign convention used everywhere: L → s = +1, R → s = -1. Units are metres.
import * as THREE from 'three';

// Envelope spec — the space each region may occupy, so neighbouring regions meet cleanly.
// All values are in the local frame of the named joint (rest orientation = suit space).
export const DIMS = {
  height: 1.96,
  // joint rest positions, relative to parent
  pelvisY: 1.0,
  spine: [0, 0.12, 0], // from pelvis
  chest: [0, 0.2, 0], // from spine      → world y 1.32
  neck: [0, 0.3, -0.01], // from chest   → world y 1.62 (top of the collar ring)
  head: [0, 0.09, 0.01], // from neck    → world y 1.71 (skull base pivot)
  shoulder: [0.235, 0.22, -0.01], // from chest (x mirrored) → world y 1.54
  elbow: [0, -0.31, 0], // from shoulder
  wrist: [0, -0.29, 0], // from elbow
  hip: [0.105, -0.04, 0], // from pelvis (x mirrored) → world y 0.96
  knee: [0, -0.44, 0], // from hip      → world y 0.52
  ankle: [0, -0.42, 0], // from knee    → world y 0.10
  // envelopes
  head_: { centerY: 0.115, halfW: 0.105, halfD: 0.125, topY: 0.25, neckR: 0.055 }, // in head joint space
  chest_: { halfW: 0.2, frontZ: 0.15, backZ: -0.105, botY: -0.1, topY: 0.3, collarR: 0.078, socketX: 0.19 }, // in chest joint space
  abdomen_: { halfW: 0.135, halfD: 0.105, botY: -0.02, topY: 0.12 }, // in spine joint space
  pelvis_: { halfW: 0.17, halfD: 0.115, botY: -0.13, topY: 0.1 }, // in pelvis joint space
  back_: { halfW: 0.175, frontZ: -0.1, backZ: -0.25, botY: -0.12, topY: 0.3 }, // in chest joint space (backpack volume)
  shoulder_: { ballR: 0.062, pauldronR: 0.115 }, // around shoulder joint
  upperArm_: { r: 0.058, len: 0.31 },
  forearm_: { rTop: 0.056, rBot: 0.044, armourR: 0.068, len: 0.29 },
  hand_: { palmW: 0.092, palmL: 0.1, palmT: 0.034, fingerL: 0.09, len: 0.19 }, // extends -Y from the wrist
  thigh_: { rTop: 0.088, rBot: 0.07, len: 0.44 },
  knee_: { r: 0.062 },
  shin_: { rTop: 0.072, rBot: 0.054, len: 0.42 },
  boot_: { soleY: -0.1, toeZ: 0.21, heelZ: -0.09, halfW: 0.058, ankleR: 0.055 }, // in ankle joint space
};

const TREE = [
  ['pelvis', 'root', [0, DIMS.pelvisY, 0]],
  ['spine', 'pelvis', DIMS.spine],
  ['chest', 'spine', DIMS.chest],
  ['neck', 'chest', DIMS.neck],
  ['head', 'neck', DIMS.head],
  ...['L', 'R'].flatMap((S) => {
    const s = S === 'L' ? 1 : -1;
    const mx = (v) => [v[0] * s, v[1], v[2]];
    return [
      ['shoulder' + S, 'chest', mx(DIMS.shoulder)],
      ['elbow' + S, 'shoulder' + S, DIMS.elbow],
      ['wrist' + S, 'elbow' + S, DIMS.wrist],
      ['hip' + S, 'pelvis', mx(DIMS.hip)],
      ['knee' + S, 'hip' + S, DIMS.knee],
      ['ankle' + S, 'knee' + S, DIMS.ankle],
    ];
  }),
];

// Poses are Euler XYZ rotations (radians) per joint. Rotation cheatsheet for a limb that
// hangs along -Y:  +Z rot swings it toward +X,  -X rot swings it forward (+Z),
// +X rot on the head pitches the face down.
const STAND = {
  shoulderL: [0.03, 0, 0.16], shoulderR: [0.03, 0, -0.16],
  elbowL: [-0.16, 0, 0], elbowR: [-0.16, 0, 0],
  hipL: [0, 0, 0.045], hipR: [0, 0, -0.045],
  ankleL: [0, 0, -0.045], ankleR: [0, 0, 0.045],
};
// Deltas added on top of STAND, weighted by the matching state value.
const DELTAS = {
  hero: {
    spine: [-0.05, 0, 0], chest: [-0.06, 0, 0], neck: [0.02, 0, 0], head: [0.1, 0, 0],
    shoulderL: [0.1, 0, 0.2], shoulderR: [0.1, 0, -0.2],
    elbowL: [-0.42, 0, 0], elbowR: [-0.42, 0, 0],
    wristL: [0, 0.25, 0], wristR: [0, -0.25, 0],
    hipL: [-0.09, 0, 0.02], hipR: [-0.09, 0, -0.02],
    kneeL: [0.18, 0, 0], kneeR: [0.18, 0, 0],
    ankleL: [-0.09, 0, -0.02], ankleR: [-0.09, 0, 0.02],
  },
  presentHand: {
    shoulderR: [-0.5, 0, -0.2], elbowR: [-1.2, 0, 0], wristR: [0, -0.5, 0],
  },
  presentForearm: {
    shoulderL: [-0.22, 0, 0.12], shoulderR: [-0.22, 0, -0.12],
    elbowL: [-0.62, 0, 0], elbowR: [-0.62, 0, 0],
  },
};

// Camera focus anchors: [joint, local offset]. The camera module asks for these by name.
const ANCHORS = {
  center: ['root', [0, 1.02, 0]],
  top: ['root', [0, 1.75, 0]],
  helmet: ['head', [0, 0.12, 0.1]],
  shoulders: ['shoulderL', [0.03, 0.03, 0.02]],
  chest: ['chest', [0, 0.1, 0.14]],
  forearms: ['elbowL', [0, -0.15, 0.03]],
  fingers: ['wristR', [0, -0.11, 0]],
  back: ['chest', [0, 0.08, -0.2]],
  legs: ['kneeL', [0, -0.26, 0.06]],
  boot: ['ankleL', [0, -0.03, 0.08]],
  knee: ['kneeL', [0, 0, 0.06]],
};

export function createRig() {
  const root = new THREE.Group();
  root.name = 'suit';
  const joints = { root };
  for (const [name, parent, pos] of TREE) {
    const g = new THREE.Group();
    g.name = 'joint:' + name;
    g.position.fromArray(pos);
    g.userData.rest = pos.slice();
    joints[parent].add(g);
    joints[name] = g;
  }
  const anchors = {};
  for (const [name, [joint, off]] of Object.entries(ANCHORS)) {
    const a = new THREE.Object3D();
    a.name = 'anchor:' + name;
    a.position.fromArray(off);
    joints[joint].add(a);
    anchors[name] = a;
  }

  const tmp = new THREE.Vector3();
  const rot = {};
  function setPose(state) {
    for (const name in joints) if (name !== 'root') rot[name] = [0, 0, 0];
    for (const name in STAND) rot[name] = STAND[name].slice();
    for (const key in DELTAS) {
      const w = state[key] || 0;
      if (w === 0) continue;
      const d = DELTAS[key];
      for (const name in d) for (let i = 0; i < 3; i++) rot[name][i] += d[name][i] * w;
    }
    for (const name in rot) joints[name].rotation.set(rot[name][0], rot[name][1], rot[name][2]);
    // keep the soles planted: drop/raise the pelvis so the ankles sit at their rest height
    joints.pelvis.position.y = DIMS.pelvisY;
    root.updateMatrixWorld(true);
    const rootY = root.getWorldPosition(tmp).y;
    const ankleY = joints.ankleL.getWorldPosition(tmp).y - rootY;
    joints.pelvis.position.y = DIMS.pelvisY + (0.1 - ankleY);
    root.updateMatrixWorld(true);
  }

  return {
    root, joints, anchors, DIMS, setPose,
    /** World position of a named focus anchor. */
    anchor(name, target = new THREE.Vector3()) {
      return (anchors[name] || anchors.center).getWorldPosition(target);
    },
  };
}
