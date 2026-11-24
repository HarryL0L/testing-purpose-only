// Material library (placeholder look — the look-dev pass replaces the implementations but
// MUST keep every name below, the `uniforms` object and `update(state)`).
import * as THREE from 'three';

export function createMaterials({ renderer } = {}) {
  // Shared uniforms, written once per frame in update(state).
  const uniforms = {
    uTime: { value: 0 },
    uEnergy: { value: 0.35 }, // global energy-line level
    uPulse: { value: -1 }, // travelling pulse position 0..1 (feet → head), -1 = off
    uCore: { value: 0.6 }, // power-core glow level
    uThrust: { value: 0 }, // thruster plume level
  };
  const P = (o) => new THREE.MeshPhysicalMaterial(o);
  const mat = {
    uniforms,
    titanium: P({ color: 0xb9bcc2, metalness: 1, roughness: 0.38 }), // brushed titanium — primary armour
    titaniumDark: P({ color: 0x5b5f68, metalness: 1, roughness: 0.42 }), // darker titanium trim / secondary plates
    cobalt: P({ color: 0x0b2a78, metalness: 0.9, roughness: 0.3, clearcoat: 0.6, clearcoatRoughness: 0.2 }), // deep cobalt metallic armour
    cobaltDark: P({ color: 0x06153f, metalness: 0.9, roughness: 0.36, clearcoat: 0.4 }), // near-navy cobalt for recesses / under-plates
    frame: P({ color: 0x1b1d22, metalness: 0.85, roughness: 0.55 }), // inner frame: dark anodised structure
    steel: P({ color: 0xd4d7dc, metalness: 1, roughness: 0.18 }), // polished steel: pistons, shafts, bolts
    rubber: P({ color: 0x0c0c0e, metalness: 0, roughness: 0.9 }), // joint gaiters, cable sheaths
    copper: P({ color: 0xb8683a, metalness: 1, roughness: 0.35 }), // windings, bus bars
    wire: P({ color: 0x15171c, metalness: 0.1, roughness: 0.6 }), // wiring looms
    wireTeal: P({ color: 0x0f6f73, metalness: 0.1, roughness: 0.5 }), // accent-coloured signal wires
    energy: P({ color: 0x021c1c, emissive: 0x19f0e0, emissiveIntensity: 1.5, roughness: 0.4 }), // teal energy lines (pulse-driven)
    energyDim: P({ color: 0x021c1c, emissive: 0x19f0e0, emissiveIntensity: 0.5, roughness: 0.4 }), // fainter teal accents / status lights
    core: P({ color: 0x0a2a2a, emissive: 0x7ffff2, emissiveIntensity: 3, roughness: 0.3 }), // power core plasma
    glass: P({ color: 0x05080c, metalness: 0.2, roughness: 0.05, emissive: 0x19f0e0, emissiveIntensity: 0.25 }), // visor / lenses
    thruster: P({ color: 0x3a3430, metalness: 1, roughness: 0.5 }), // heat-stained nozzle metal
    thrusterGlow: P({ color: 0x000000, emissive: 0x6ffcf0, emissiveIntensity: 0, transparent: true, opacity: 0.9, depthWrite: false }), // plume / nozzle interior glow
  };
  mat.update = (state) => {
    uniforms.uTime.value = state.t;
    uniforms.uEnergy.value = state.energy;
    uniforms.uPulse.value = state.pulse;
    uniforms.uCore.value = state.core;
    uniforms.uThrust.value = state.thrust;
    mat.energy.emissiveIntensity = 4 * state.energy;
    mat.energyDim.emissiveIntensity = 1.4 * state.energy;
    mat.core.emissiveIntensity = 5 * state.core;
    mat.thrusterGlow.emissiveIntensity = 6 * state.thrust;
    mat.thrusterGlow.opacity = Math.min(1, state.thrust * 1.2);
  };
  return mat;
}
