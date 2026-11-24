// Camera choreography (placeholder — the camera pass replaces this). Contract:
//   createCameraRig(ctx) → { camera, update(state, frame) → shot }
//   shot = { focus: metres to the plane of sharp focus, blur: 0..1 depth-of-field strength,
//            cut: true on the first frame after a hard cut }
//   frame = { suit, env, aspect, renderer, scene } ; suit.anchor(name, vec3) gives world focus points.
import * as THREE from 'three';
import * as TL from '../core/timeline.js';

export function createCameraRig(ctx) {
  const camera = new THREE.PerspectiveCamera(32, 16 / 9, 0.05, 200);
  const target = new THREE.Vector3();
  const a = new THREE.Vector3();
  return {
    camera,
    update(state, frame) {
      camera.aspect = frame.aspect;
      camera.fov = 32;
      const wide = frame.suit.anchor('center', a).clone();
      let dist = 5.4 - 0.5 * state.hero + 1.2 * state.explode;
      let az = 0.5 + state.t * 0.09;
      target.copy(wide);
      if (state.active) {
        const u = state.section[state.active];
        const k = TL.smoothstep(TL.clamp01(Math.min(u, 1 - u) / 0.2));
        frame.suit.anchor(state.active, a);
        target.lerp(a, k);
        dist = TL.lerp(dist, 1.5, k);
        if (state.active === 'back') az += Math.PI * k;
      }
      camera.position.set(target.x + Math.sin(az) * dist, target.y + 0.25, target.z + Math.cos(az) * dist);
      camera.lookAt(target);
      camera.updateProjectionMatrix();
      return { focus: camera.position.distanceTo(target), blur: state.active ? 0.6 : 0.2, cut: false };
    },
  };
}
