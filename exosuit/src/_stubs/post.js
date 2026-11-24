// Post-processing (placeholder — the post pass replaces this). Contract:
//   createPost({ renderer, scene, camera }) → { setSize(w, h), render(state, shot) }
//   setSize receives the drawing-buffer size in physical pixels.
//   render must draw the final image to the canvas; shot = { focus, blur, cut } from the camera.
import * as THREE from 'three';

export function createPost({ renderer, scene, camera }) {
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  return {
    setSize(w, h) {},
    render(state, shot) {
      renderer.render(scene, camera);
    },
  };
}
