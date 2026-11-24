// Reflection environment — a bespoke "hangar light rig" built procedurally and baked through
// PMREMGenerator.fromScene (no image files). Metal only looks as good as what it reflects, so
// this is laid out like a studio car shoot inside a dark hangar:
//
//   · KEY     tall warm softbox, front / suit's-left   → the long streak highlight down the armour
//   · FILL    narrow cool strip, front / suit's-right  → a second, thinner streak on the other side
//   · RIMS    two tall hot strips behind (ice blue on one side, teal-white on the other) → edge light
//   · TOP     slatted cool overhead panel + long ceiling tubes down both sides → highlights on
//             helmet / shoulders, long parallel streaks along limbs
//   · LOW     teal platform ring and a faint teal wash low on the back wall → teal accent from below
//   · the rest is near-black hangar structure (ribs, gantry beams, a few tiny practical lamps) so
//     reflections stay contrasty and dramatic.
//
// World frame: +Y up, the suit stands at the origin facing +Z. The probe is taken at chest
// height (EYE_Y). Contract: createEnvironmentMap(renderer) → PMREM texture for scene.environment.
// Everything here is deterministic (seeded PRNG) and runs once at startup.
//
// Levels are linear radiance, balanced for ACES at exposure ≈ 1 with scene.environmentIntensity 1:
// the average over the sphere is only about 0.25, the key panel is ≈ 5.5 and the rims 13–19, so
// brushed titanium shows mid-grey body tone with bright streaks, and polished steel / clear coat /
// visor glass mirror the panels hot enough to catch the bloom.
import * as THREE from 'three';

const EYE_Y = 1.15; // probe height in metres (suit chest)

function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function createEnvironmentMap(renderer) {
  const scene = new THREE.Scene();
  const root = new THREE.Group();
  root.position.y = -EYE_Y; // author in world coordinates, probe sits at the origin
  scene.add(root);
  const trash = [];
  const basic = (opts) => {
    const m = new THREE.MeshBasicMaterial({ side: THREE.DoubleSide, fog: false, toneMapped: false, ...opts });
    trash.push(m);
    return m;
  };
  const centre = new THREE.Vector3(0, EYE_Y, 0);

  // ---- shell: a big dark dome with a faint cool gradient (never pure black) ------------
  {
    const g = new THREE.SphereGeometry(40, 48, 24);
    const pos = g.attributes.position;
    const col = new Float32Array(pos.count * 3);
    for (let i = 0; i < pos.count; i++) {
      const y = pos.getY(i) / 40; // -1 (floor) … 1 (zenith)
      const z = pos.getZ(i) / 40;
      const horizon = Math.exp(-Math.pow((y - 0.02) / 0.16, 2)); // haze glow sitting on the horizon
      const up = Math.max(0, y);
      const back = 0.5 - 0.5 * z; // slightly more haze glow behind the suit (rim side)
      // dim bounce off the roof structure: lifts the metal's body tone just above pure black
      const roof = 0.022 * Math.pow(up, 0.7);
      col[i * 3] = 0.007 + 0.020 * horizon * (0.5 + back) + roof * 0.8;
      col[i * 3 + 1] = 0.009 + 0.028 * horizon * (0.5 + back) + roof * 0.92;
      col[i * 3 + 2] = 0.013 + 0.042 * horizon * (0.5 + back) + roof * 1.15;
      if (y < -0.03) { // floor: dark, faintly warm concrete
        col[i * 3] = 0.016; col[i * 3 + 1] = 0.015; col[i * 3 + 2] = 0.015;
      }
    }
    g.setAttribute('color', new THREE.BufferAttribute(col, 3));
    trash.push(g);
    root.add(new THREE.Mesh(g, basic({ vertexColors: true, side: THREE.BackSide })));
  }

  // ---- emitter helper: a rectangle facing `aim`, brighter in the middle like a real softbox ---
  // falloff 0 = hard uniform panel, 1 = strong centre-weighted softbox.
  function softbox(w, h, rgb, pos, aim = centre, falloff = 0.5) {
    const g = new THREE.PlaneGeometry(w, h, 10, 10);
    const p = g.attributes.position;
    const col = new Float32Array(p.count * 3);
    for (let i = 0; i < p.count; i++) {
      const u = p.getX(i) / (w / 2), v = p.getY(i) / (h / 2);
      const edge = Math.max(Math.abs(u), Math.abs(v));
      const k = 1 - falloff * Math.pow(edge, 2.2) * 0.85;
      col[i * 3] = rgb[0] * k; col[i * 3 + 1] = rgb[1] * k; col[i * 3 + 2] = rgb[2] * k;
    }
    g.setAttribute('color', new THREE.BufferAttribute(col, 3));
    trash.push(g);
    const m = new THREE.Mesh(g, basic({ vertexColors: true }));
    m.position.fromArray(pos);
    m.lookAt(aim);
    root.add(m);
    return m;
  }
  const box = (sx, sy, sz, rgb, pos) => {
    const g = new THREE.BoxGeometry(sx, sy, sz);
    trash.push(g);
    const m = new THREE.Mesh(g, basic({ color: new THREE.Color().setRGB(rgb[0], rgb[1], rgb[2]) }));
    m.position.fromArray(pos);
    root.add(m);
    return m;
  };

  // ---- KEY: tall warm softbox, front-left of the suit (camera right in the opening shot) ----
  softbox(1.05, 4.2, [5.6, 5.0, 4.3], [3.9, 2.5, 4.4], centre, 0.6);
  // a thin hot "kicker" tube beside it gives the key highlight a crisp bright edge on polished parts
  softbox(0.1, 4.4, [10.5, 9.7, 8.5], [2.75, 2.5, 5.1], centre, 0.2);

  // ---- FILL: narrow cool strip, front-right of the suit --------------------------------
  softbox(0.36, 4.6, [1.5, 2.0, 2.8], [-4.6, 2.3, 3.0], centre, 0.35);
  softbox(0.08, 3.2, [3.0, 3.9, 5.2], [-2.2, 2.0, 5.6], centre, 0.2);

  // ---- RIMS: strong sources behind, either side ------------------------------------------
  softbox(0.42, 5.6, [11, 14, 19], [-3.3, 2.9, -5.0], centre, 0.3); // ice-blue rim
  softbox(0.3, 5.0, [3.5, 13, 12], [4.3, 2.4, -4.3], centre, 0.3); // teal-white rim
  softbox(0.1, 3.6, [13, 15, 18], [0.9, 3.4, -6.4], centre, 0.2); // thin back hair-light

  // ---- TOP: slatted cool overhead panel + long ceiling tubes -------------------------------
  const down = new THREE.Vector3();
  for (let i = 0; i < 3; i++) {
    const z = -0.9 + i * 1.05;
    softbox(3.0, 0.42, [3.0, 3.5, 4.4], [0.2, 6.4, z], down.set(0.2, 0, z), 0.4);
  }
  for (const sx of [-1, 1]) {
    // tubes run front-to-back high on each side: long parallel streaks along shoulders / thighs
    softbox(0.09, 15, [4.6, 5.0, 5.9], [sx * 3.1, 5.1, 0.5], down.set(sx * 3.1, 0, 0.5), 0.1);
    softbox(15, 0.07, [2.6, 2.9, 3.5], [sx * 5.4, 4.2, 0.5], down.set(sx * 2.0, 0, 0.5), 0.1);
  }

  // ---- LOW: teal accent from the platform and the back wall ---------------------------------
  {
    const g = new THREE.TorusGeometry(1.72, 0.035, 6, 72);
    trash.push(g);
    const ring = new THREE.Mesh(g, basic({ color: new THREE.Color().setRGB(0.02, 0.6, 0.51) }));
    ring.rotation.x = Math.PI / 2;
    ring.position.y = -0.06;
    root.add(ring);
    // platform top: dim, catches the key and bounces a little light back up into the suit
    const disc = new THREE.CircleGeometry(1.45, 48);
    trash.push(disc);
    const top = new THREE.Mesh(disc, basic({ color: new THREE.Color().setRGB(0.024, 0.025, 0.028) }));
    top.rotation.x = -Math.PI / 2;
    top.position.y = -0.01;
    root.add(top);
  }
  softbox(11, 1.1, [0.01, 0.26, 0.22], [0, 0.55, -9.5], new THREE.Vector3(0, 0.55, 0), 0.7); // back-wall teal wash
  softbox(0.14, 2.4, [0.1, 2.6, 2.2], [-6.5, 1.3, -6.5], centre, 0.2); // teal service light, back corner
  softbox(0.14, 2.4, [0.1, 2.1, 1.8], [7.2, 1.3, 1.5], centre, 0.2);

  // ---- dark hangar structure: ribs + gantry so the black isn't featureless ---------------
  const rand = prng(0xe505017);
  for (let i = 0; i < 14; i++) {
    const a = (i / 14) * Math.PI * 2 + 0.13;
    const r = 11.5;
    const lit = 0.022 + 0.03 * rand();
    box(0.7, 12, 0.7, [lit * 0.8, lit * 0.95, lit * 1.25], [Math.sin(a) * r, 6, Math.cos(a) * r]).rotation.y = a;
  }
  box(26, 0.5, 0.6, [0.03, 0.036, 0.048], [0, 7.6, -4.0]);
  box(26, 0.5, 0.6, [0.03, 0.036, 0.048], [0, 7.6, 3.6]);
  box(0.6, 0.5, 26, [0.026, 0.03, 0.04], [-6.5, 7.9, 0]);
  box(0.6, 0.5, 26, [0.026, 0.03, 0.04], [6.5, 7.9, 0]);

  // ---- practicals: tiny hot lamps far away → pin-sharp sparkle on polished steel / visor ----
  for (let i = 0; i < 16; i++) {
    const a = rand() * Math.PI * 2;
    const r = 9 + rand() * 2.5;
    const y = 2.2 + rand() * 4.5;
    const warm = rand() < 0.3;
    const e = 12 + rand() * 22;
    const rgb = warm ? [e, e * 0.52, e * 0.16] : [e * 0.75, e * 0.9, e];
    softbox(0.09, 0.09, rgb, [Math.sin(a) * r, y, Math.cos(a) * r], centre, 0);
  }
  // one sodium work-lamp glow low in a back corner: a small warm counterpoint to all the cyan
  softbox(0.22, 0.16, [7, 2.9, 0.7], [7.6, 0.9, -5.6], centre, 0.6);

  const pmrem = new THREE.PMREMGenerator(renderer);
  const tex = pmrem.fromScene(scene, 0, 0.1, 100).texture;
  pmrem.dispose();
  for (const d of trash) d.dispose();
  return tex;
}
