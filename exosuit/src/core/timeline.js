// Timeline: the whole film is a pure function of time t (seconds, 0..DURATION).
// Nothing in the project may accumulate state between frames — every module derives
// its pose from the `state` object produced by computeState(t). This is what makes the
// film scrubbable and lets the offline renderer step frames deterministically.

export const DURATION = 20;
export const FPS = 60;

export const PHASES = {
  turntable: [0.0, 4.0], // assembled suit, platform turns a full 360°
  explode: [4.0, 6.2], // every armour panel separates and floats outward
  showcase: [6.2, 14.95], // seven sections articulate in turn
  reassemble: [14.95, 16.6], // fast mechanical snap-back
  hero: [16.6, 20.0], // hero pose, energy lines pulse
};

export const SECTIONS = ['helmet', 'shoulders', 'chest', 'forearms', 'fingers', 'back', 'legs'];
export const SECTION_LEN = (PHASES.showcase[1] - PHASES.showcase[0]) / SECTIONS.length; // 1.25 s
export const SECTION_TIME = Object.fromEntries(
  SECTIONS.map((name, i) => [
    name,
    [PHASES.showcase[0] + i * SECTION_LEN, PHASES.showcase[0] + (i + 1) * SECTION_LEN],
  ]),
);

export const SECTION_LABEL = {
  helmet: 'Helmet · faceplate actuation',
  shoulders: 'Shoulders · pauldron lift',
  chest: 'Chest · core access',
  forearms: 'Forearms · panel deployment',
  fingers: 'Hands · digit articulation',
  back: 'Back · flaps and thrusters',
  legs: 'Legs · plate shift and lock',
};

// ---- math helpers -------------------------------------------------------------------
export const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
export const clamp01 = (x) => Math.min(1, Math.max(0, x));
export const lerp = (a, b, u) => a + (b - a) * u;
export const seg = (t, a, b) => clamp01((t - a) / (b - a)); // progress of t through [a,b]
export const remap = (x, a, b, c, d) => c + (d - c) * clamp01((x - a) / (b - a));
export const smoothstep = (u) => u * u * (3 - 2 * u);
export const smootherstep = (u) => u * u * u * (u * (u * 6 - 15) + 10);
export const easeInQuad = (u) => u * u;
export const easeOutQuad = (u) => 1 - (1 - u) * (1 - u);
export const easeInCubic = (u) => u * u * u;
export const easeOutCubic = (u) => 1 - Math.pow(1 - u, 3);
export const easeInOutCubic = (u) => (u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2);
export const easeInQuart = (u) => u * u * u * u;
export const easeOutQuart = (u) => 1 - Math.pow(1 - u, 4);
export const easeInOutQuart = (u) => (u < 0.5 ? 8 * u * u * u * u : 1 - Math.pow(-2 * u + 2, 4) / 2);
export const easeOutExpo = (u) => (u >= 1 ? 1 : 1 - Math.pow(2, -10 * u));
export const easeInExpo = (u) => (u <= 0 ? 0 : Math.pow(2, 10 * u - 10));
export const easeOutBack = (u, s = 1.70158) => 1 + (s + 1) * Math.pow(u - 1, 3) + s * Math.pow(u - 1, 2);
export const easeInBack = (u, s = 1.70158) => (s + 1) * u * u * u - s * u * u;

// Mechanical move: accelerates, travels, then seats with a small damped overshoot.
// Reads as "weighty actuator reaching its end stop". u in 0..1 -> 0..1.
export function mech(u, overshoot = 0.035) {
  u = clamp01(u);
  const main = easeInOutCubic(clamp01(u / 0.82));
  const k = clamp01((u - 0.82) / 0.18);
  return main + overshoot * Math.sin(k * Math.PI) * (1 - k);
}

// Open → hold → close envelope for a showcase section. u is the raw 0..1 progress
// through the section window; returns 0 (closed) → 1 (open) → 0 (closed).
export function openHoldClose(u, openEnd = 0.36, closeStart = 0.66, ease = mech) {
  if (u <= 0 || u >= 1) return 0;
  if (u < openEnd) return ease(u / openEnd);
  if (u < closeStart) return 1;
  return 1 - ease((u - closeStart) / (1 - closeStart));
}

// Sub-window helper: progress of u through [a,b], for sequencing sub-motions
// ("finger 1, then finger 2…") inside one section.
export const sub = (u, a, b) => clamp01((u - a) / (b - a));

// Cheap deterministic hash → 0..1 (use for per-part phase offsets; never Math.random()).
export function hash(n) {
  const s = Math.sin(n * 127.1 + 311.7) * 43758.5453123;
  return s - Math.floor(s);
}

// ---- state --------------------------------------------------------------------------
export function phaseAt(t) {
  for (const [name, [a, b]] of Object.entries(PHASES)) if (t >= a && t < b) return name;
  return t < 0 ? 'turntable' : 'hero';
}

/**
 * computeState(t) → the single source of truth handed to every module each frame.
 *
 * @returns {{
 *   t:number, phase:string, phaseU:number,
 *   turn:number,            // platform yaw in radians (0 → 2π over the turntable phase, then 2π)
 *   explode:number,         // 0 assembled … 1 fully exploded (global envelope, already eased)
 *   explodeU:number,        // raw 0..1 progress of the explode phase (0 before, 1 after)
 *   reassembleU:number,     // raw 0..1 progress of the reassemble phase (0 before, 1 after)
 *   section:Record<string,number>, // raw 0..1 progress inside each showcase window; 0 when inactive
 *   active:string|null,     // name of the section currently being showcased
 *   hero:number,            // 0..1 blend into the hero pose (eased)
 *   heroU:number,           // raw 0..1 progress of the hero phase
 *   energy:number,          // global energy-line level (≈0.35 idle, up to 1 in hero)
 *   pulse:number,           // travelling pulse position 0..1 (feet → head), repeats in hero; -1 when off
 *   core:number,            // power-core glow level 0..1.5
 *   thrust:number,          // thruster plume level 0..1
 *   presentHand:number,     // 0..1 right forearm raised to present the hand to camera
 *   presentForearm:number,  // 0..1 both forearms slightly raised for the forearm section
 * }}
 */
export function computeState(t) {
  t = clamp(t, 0, DURATION);
  const phase = phaseAt(t);
  const [pa, pb] = PHASES[phase];
  const phaseU = seg(t, pa, pb);

  // Platform: already turning at t=0, decelerates into its detent at the end of the phase.
  const tu = seg(t, ...PHASES.turntable);
  const turn = Math.PI * 2 * (1 - Math.pow(1 - tu, 1.7));

  const explodeU = seg(t, ...PHASES.explode);
  const reassembleU = seg(t, ...PHASES.reassemble);
  const explode = t < PHASES.reassemble[0] ? easeInOutCubic(explodeU) : 1 - reassembleU;

  const section = {};
  let active = null;
  for (const name of SECTIONS) {
    const [a, b] = SECTION_TIME[name];
    const inside = t >= a && t < b;
    section[name] = inside ? (t - a) / (b - a) : 0;
    if (inside) active = name;
  }

  const heroU = seg(t, ...PHASES.hero);
  const hero = mech(seg(t, PHASES.hero[0], PHASES.hero[0] + 1.1), 0.02);

  // Energy: dim idle glow, flickers up as panels re-seat, then pulses in the hero phase.
  const idle = 0.32 + 0.04 * Math.sin(t * 2.1);
  const heroRamp = smoothstep(seg(t, PHASES.hero[0] + 0.2, PHASES.hero[0] + 1.3));
  const beat = t > PHASES.hero[0] + 0.6 ? Math.pow(0.5 + 0.5 * Math.sin((t - PHASES.hero[0] - 0.6) * 5.2), 2) : 0;
  const energy = idle + heroRamp * (0.45 + 0.25 * beat) + 0.25 * smoothstep(reassembleU) * (1 - heroRamp);

  // Travelling pulse: three sweeps feet → head during the hero phase.
  const pulseT = (t - (PHASES.hero[0] + 0.7)) / 0.9;
  const pulse = pulseT >= 0 && pulseT < 3 ? pulseT - Math.floor(pulseT) : -1;

  // Core is exposed in the exploded view: glow harder there and in the hero beat.
  const core = 0.55 + 0.35 * explode + 0.25 * openHoldClose(section.chest, 0.3, 0.7, smoothstep) + heroRamp * (0.35 + 0.2 * beat);

  const backOpen = openHoldClose(section.back, 0.4, 0.72, smoothstep);
  const thrust = Math.max(0.85 * smoothstep(sub(section.back, 0.38, 0.6)) * backOpen, heroRamp * (0.55 + 0.25 * beat));

  const presentHand = openHoldClose(seg(t, SECTION_TIME.fingers[0] - 0.3, SECTION_TIME.fingers[1] + 0.25), 0.2, 0.8, easeInOutCubic);
  const presentForearm = openHoldClose(seg(t, SECTION_TIME.forearms[0] - 0.25, SECTION_TIME.forearms[1] - 0.05), 0.2, 0.75, easeInOutCubic);

  return {
    t, phase, phaseU, turn, explode, explodeU, reassembleU, section, active,
    hero, heroU, energy, pulse, core, thrust, presentHand, presentForearm,
  };
}
