// HUD / transport (placeholder — the UI pass replaces this). Contract:
//   createHud({ root, overlay, frame, player }) → { update(state, player) }
//   root    = the #hud element (transport controls; spans the whole page bottom).
//   overlay = the #overlay element: absolutely fills #frame, i.e. exactly the film picture
//             (letterboxed 1:1 … 2.4:1). Burned-in film graphics (captions) go here; it is
//             captured into the rendered video, the controls are not.
//   frame   = the #frame element (sized by main.js).
//   player  = { duration, time, playing, quality, play(), pause(),
//            toggle(), seek(t), setQuality(q), capture }
//   update is called every rendered frame. In capture mode (player.capture) show no controls.
export function createHud({ root, overlay, frame, player }) {
  if (player.capture) return { update() {} };
  root.innerHTML = '<button id="exo-play" type="button">Pause</button><input id="exo-seek" type="range" min="0" max="20" step="0.01" value="0" aria-label="Timeline">';
  const btn = root.querySelector('#exo-play');
  const seek = root.querySelector('#exo-seek');
  btn.addEventListener('click', () => player.toggle());
  seek.addEventListener('input', () => player.seek(parseFloat(seek.value)));
  return {
    update(state, player) {
      btn.textContent = player.playing ? 'Pause' : 'Play';
      if (document.activeElement !== seek) seek.value = state.t;
    },
  };
}
