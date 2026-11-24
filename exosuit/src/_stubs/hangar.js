// Hangar set (placeholder — the set pass replaces this). Contract:
//   buildHangar(ctx) → { group, platform, backdrop, update(state, frame) }
//   group     everything, added to the scene by main.js
//   platform  THE ROTATING turntable top (Object3D at world origin, top surface at y = 0).
//             main.js parents the suit to it and sets platform.rotation.y = state.turn.
//   backdrop  sub-group holding architecture / haze / sparks that ?noenv=1 hides
//             (lights and the platform must NOT be inside it)
import * as THREE from 'three';

export function buildHangar(ctx) {
  const group = new THREE.Group();
  const backdrop = new THREE.Group();
  group.add(backdrop);

  const floor = new THREE.Mesh(new THREE.PlaneGeometry(60, 60), new THREE.MeshStandardMaterial({ color: 0x0b0d10, roughness: 0.55, metalness: 0.3 }));
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = -0.35;
  floor.receiveShadow = true;
  backdrop.add(floor);

  const base = new THREE.Mesh(new THREE.CylinderGeometry(1.45, 1.6, 0.3, 64), new THREE.MeshStandardMaterial({ color: 0x15181d, roughness: 0.5, metalness: 0.8 }));
  base.position.y = -0.2;
  base.receiveShadow = true;
  group.add(base);

  const platform = new THREE.Group();
  const top = new THREE.Mesh(new THREE.CylinderGeometry(1.3, 1.3, 0.05, 64), new THREE.MeshStandardMaterial({ color: 0x23272e, roughness: 0.4, metalness: 0.9 }));
  top.position.y = -0.025;
  top.receiveShadow = true;
  platform.add(top);
  const mark = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.012, 0.5), new THREE.MeshStandardMaterial({ color: 0x0a2a2a, emissive: 0x19f0e0, emissiveIntensity: 2 }));
  mark.position.set(0, 0.002, 1.0);
  platform.add(mark);
  group.add(platform);

  const key = new THREE.SpotLight(0xfff1e0, 120, 30, 0.5, 0.6, 2);
  key.position.set(3.5, 5.5, 4.5);
  key.target.position.set(0, 1, 0);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  key.shadow.bias = -0.0004;
  group.add(key, key.target);
  const rimA = new THREE.DirectionalLight(0x9fd8ff, 3.2);
  rimA.position.set(-4, 3.5, -5);
  const rimB = new THREE.DirectionalLight(0x30fff0, 2.2);
  rimB.position.set(4.5, 2.5, -4.5);
  group.add(rimA, rimB, new THREE.HemisphereLight(0x6f8fb0, 0x08090c, 0.35));

  return {
    group, platform, backdrop,
    fog: new THREE.FogExp2(0x05070a, 0.045),
    background: new THREE.Color(0x05070a),
    update(state, frame) {},
  };
}
