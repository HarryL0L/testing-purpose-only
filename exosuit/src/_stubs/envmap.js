// Reflection environment (placeholder — the look-dev pass replaces this with a bespoke
// hangar light-rig environment). Must return a PMREM-filtered texture for scene.environment.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';

export function createEnvironmentMap(renderer) {
  const pmrem = new THREE.PMREMGenerator(renderer);
  const tex = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  pmrem.dispose();
  return tex;
}
