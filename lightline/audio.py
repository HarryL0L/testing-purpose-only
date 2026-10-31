"""
lightline.audio — a generative ambient score for the short film ONE LINE (103.0 s).

    python3 -m lightline.audio --out output/score.wav

Deterministic (seeded), numpy + scipy only. Renders a 48 kHz / 16-bit stereo WAV of
exactly 103.0 s (4,944,000 samples per channel) and prints a short loudness analysis.

Musical design
--------------
Key: D minor, modal (Aeolian with the relative-major colour of F / Bb), A = 440 Hz.
Everything sits over a D pedal; the one crescendo (the flame tip at 55.0 s) opens the
pad from an open-fifth D-add9 voicing into Bb(add9) over the D pedal — a warm, major
sonority that never leaves the mode — and closes back down again.

Layers (in order of appearance; all timings in seconds from the first frame):
  drone          deep-water bed: D1/D2/A2/D3 sines in slowly beating pairs + a faint
                 35–110 Hz "pressure" noise that swells at ~0.04 Hz.        0.0–103.0
  breath cue     the point of light pulses: a round D5 bell swell with no attack
                 transient (gamma envelopes per partial).  The SAME array is placed
                 at 1.5 s and 98.7 s.
  glass          glassy harmonics (D5…A6) blooming in and out over 4–9 s, denser and
                 shimmering (slow tremolo + chorus) during the shoal.      5.5–40
  sub pulse      the light touches the floor: a soft D2 pulse every 3.75 s. 19.5–87
  wooden pulse   muted marimba-like D3 knock through long reverb; irregular from the
                 ape, snapping onto the pulse grid (every 1.875 s) by 38.  28.5–63
  pad            additive bowed/organ pad (8 partials, per-partial amplitude drift,
                 L/R detune, soft saturation, low-pass).  Enters at 38 as D3 A3 D4 E4 A4,
                 swells into Bb add9 for 55.0–55.5, thins in the cave, returns in the
                 furrows, brightens (pale gold) in the city, gone by 92.    38–92
  crackle        sparse pitched noise grains (partials of D) under the fire hold,
                 plus a faint warm low bed.                                55.5–59.5
  breath texture band-passed noise inhale/exhale cycles every ~5 s.        59.5–75
  tick           soft clock (A5 / G5 tick-tock) through long reverb on a 96 bpm grid,
                 every other beat in the furrows, every beat in the city.  68.9–91
  high tone      A6 (1760 Hz) with a 20-cent "tuning-in" rise, slow vibrato and a faint
                 octave below, as the light spirals up; decays under the crane.  91–103

All pulse-like layers share one rhythmic grid anchored at 19.5 s (0.625 s = one beat at
96 bpm): sub pulse = 6 beats, wooden pulse = 3 beats, tick = 1 or 2 beats.
"""
from __future__ import annotations

import argparse
import math
import os
import sys
import time

import numpy as np
from scipy import ndimage, signal
from scipy.io import wavfile

SR = 48_000
DURATION = 103.0
N = int(round(SR * DURATION))  # 4_944_000 samples per channel
SEED = 20261031
TWO_PI = 2.0 * math.pi

# Target integrated level (RMS over both channels, whole film) and ceiling.
TARGET_RMS_DB = -19.7   # pre-limiter; the limiter's riding of the swell lands it near -20
LIMIT_DB = -2.6          # sample-peak limiter threshold
TRUE_PEAK_MAX_DB = -1.5  # hard requirement on the inter-sample peak

# Relative layer gains (pre-normalisation; only the balance matters).
LEVEL = dict(
    drone=0.17,
    breath=0.11,
    glass=0.085,
    sub=0.15,
    wood=0.19,
    pad=0.62,
    crackle=0.08,
    fire_bed=0.03,
    breath_tex=0.03,
    tick=0.26,
    high=0.034,
)

# Rhythmic grid shared by every pulse-like layer.
GRID_T0 = 19.5          # the light first touches the floor
BEAT = 0.625            # 96 bpm


# --------------------------------------------------------------------------- helpers
_NOTE_IDX = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def hz(note: str) -> float:
    """'D3', 'Bb2', 'F#4' -> frequency in Hz (A4 = 440)."""
    name, octave = note[:-1], int(note[-1])
    semis = _NOTE_IDX[name[0]] + sum(1 if c == "#" else -1 for c in name[1:])
    midi = 12 * (octave + 1) + semis
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def db(x: float) -> float:
    return 10.0 ** (x / 20.0)


def to_db(x: float) -> float:
    return 20.0 * math.log10(max(float(x), 1e-12))


def n_of(seconds: float) -> int:
    return int(round(seconds * SR))


def times(n: int, t0: float = 0.0) -> np.ndarray:
    return t0 + np.arange(n) / SR


def raised_cosine(n_total: int, n_in: int, n_out: int) -> np.ndarray:
    """Flat envelope with half-cosine fade-in / fade-out (first and last sample reach 0)."""
    env = np.ones(n_total)
    n_in = int(min(n_in, n_total))
    n_out = int(min(n_out, n_total))
    if n_in > 1:
        env[:n_in] = 0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, n_in))
    if n_out > 1:
        env[n_total - n_out:] *= 0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, n_out))
    return env


def gamma_env(t: np.ndarray, tp: float, p: float) -> np.ndarray:
    """Smooth swell that peaks at 1.0 at t = tp and decays — no attack transient."""
    tt = np.maximum(t, 0.0) / tp
    return tt ** p * np.exp(p * (1.0 - tt))


def breakpoints(points, n: int, t0: float = 0.0) -> np.ndarray:
    """Piecewise half-cosine interpolation of (time, value) pairs, sampled at SR from t0."""
    pts = sorted(points)
    tt = np.array([p[0] for p in pts], dtype=float)
    vv = np.array([p[1] for p in pts], dtype=float)
    t = times(n, t0)
    idx = np.clip(np.searchsorted(tt, t, side="right") - 1, 0, len(tt) - 2)
    ta, tb = tt[idx], tt[idx + 1]
    va, vb = vv[idx], vv[idx + 1]
    u = np.clip((t - ta) / np.maximum(tb - ta, 1e-9), 0.0, 1.0)
    return va + (vb - va) * (0.5 - 0.5 * np.cos(np.pi * u))


def db_env(points, n: int, t0: float = 0.0) -> np.ndarray:
    """breakpoints() in dB, returned linear (use -90 for 'silence')."""
    return db(0.0) * 10.0 ** (breakpoints(points, n, t0) / 20.0)


def slow_noise(n: int, rng: np.random.Generator, smooth_hz: float = 0.1, ctrl_hz: float = 25.0) -> np.ndarray:
    """Band-limited random drift (~unit std) at audio rate, generated at a low control rate."""
    pad = int(ctrl_hz / max(smooth_hz, 1e-3)) * 3 + 32
    m = int(math.ceil(n / SR * ctrl_hz)) + 2 * pad
    x = rng.standard_normal(m)
    sos = signal.butter(2, min(smooth_hz / (ctrl_hz / 2.0), 0.9), output="sos")
    y = signal.sosfiltfilt(sos, x)[pad:pad + int(math.ceil(n / SR * ctrl_hz)) + 2]
    y = y / (np.std(y) + 1e-9)
    tc = np.arange(len(y)) / ctrl_hz
    return np.interp(np.arange(n) / SR, tc, y)


def sos_of(kind: str, fc, order: int = 2):
    wn = np.asarray(fc, dtype=float) / (SR / 2.0)
    return signal.butter(order, float(wn) if wn.ndim == 0 else wn, btype=kind, output="sos")


def lowpass(x: np.ndarray, fc: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(sos_of("low", fc, order), x, axis=0)


def bandpass_noise(n: int, rng: np.random.Generator, lo: float, hi: float, order: int = 2,
                   stereo: bool = False) -> np.ndarray:
    """Unit-RMS band-passed white noise (mono (n,) or stereo (n,2), independent channels)."""
    shape = (n, 2) if stereo else (n,)
    x = rng.standard_normal(shape)
    y = signal.sosfilt(sos_of("band", [lo, hi], order), x, axis=0)
    return y / (np.sqrt(np.mean(y ** 2)) + 1e-12)


def pan2(buf: np.ndarray, pan: float) -> np.ndarray:
    """Constant-power pan of a mono buffer, pan in [-1, 1]."""
    th = (pan + 1.0) * np.pi / 4.0
    return np.stack([buf * np.cos(th), buf * np.sin(th)], axis=1)


class Mixer:
    """Three buses: dry, and two reverb sends (long and mid tails)."""

    def __init__(self):
        self.dry = np.zeros((N, 2))
        self.long = np.zeros((N, 2))
        self.mid = np.zeros((N, 2))

    def add(self, buf: np.ndarray, t0: float, gain: float = 1.0, dry: float = 1.0,
            long: float = 0.0, mid: float = 0.0, pan: float = 0.0) -> None:
        buf = np.asarray(buf, dtype=float)
        if buf.ndim == 1:
            buf = pan2(buf, pan)
        start = n_of(t0)
        end = start + len(buf)
        if start >= N or end <= 0:
            return
        s0, e0 = max(0, start), min(N, end)
        seg = buf[s0 - start:e0 - start] * gain
        for dst, w in ((self.dry, dry), (self.long, long), (self.mid, mid)):
            if w:
                dst[s0:e0] += w * seg


def make_ir(rng: np.random.Generator, rt60: float = 4.5, predelay_ms: float = 18.0,
            damp_hz: float = 3500.0, hf_ratio: float = 0.45) -> np.ndarray:
    """Synthetic stereo impulse response: exponentially decaying noise with HF damping."""
    n = n_of(rt60 * 1.05)
    t = times(n)
    noise = rng.standard_normal((n, 2))
    lo = lowpass(noise, damp_hz, 2)
    hi = noise - lo
    env_lo = np.exp(-6.908 * t / rt60)
    env_hi = np.exp(-6.908 * t / (rt60 * hf_ratio))
    ir = lo * env_lo[:, None] + hi * env_hi[:, None]
    ir *= raised_cosine(n, n_of(0.006), n_of(0.3))[:, None]
    ir = 0.65 * ir + 0.35 * ir.mean(axis=1, keepdims=True)  # partially correlated L/R, mono-safe
    ir = np.vstack([np.zeros((n_of(predelay_ms / 1000.0), 2)), ir])
    # unit power gain for tonal material in the 60 Hz - 2 kHz band (where the music lives),
    # rather than unit broadband energy, which would over-weight the damped low band
    spec = np.abs(np.fft.rfft(ir, axis=0)) ** 2
    freqs = np.fft.rfftfreq(len(ir), 1.0 / SR)
    band = (freqs > 60.0) & (freqs < 2000.0)
    ir /= math.sqrt(float(spec[band].mean()))
    return ir


# ------------------------------------------------------------------------------ layers
def layer_drone(mx: Mixer, rng: np.random.Generator) -> None:
    """Deep-water drone: beating sine pairs on D1 D2 A2 D3 + faint low 'pressure' noise."""
    n = N
    t = times(n)
    env = db_env([(0.0, -90), (0.3, -48), (1.5, -34), (6.0, -16), (11.5, -10), (19.5, -8),
                  (28.5, -8), (38.0, -9), (48.0, -8), (55.0, -8), (55.5, -8), (59.5, -9),
                  (68.5, -11), (85.5, -12), (91.0, -18), (94.5, -28), (101.5, -34), (103.0, -90)], n)
    out = np.zeros((n, 2))
    # (note, level dB, beat Hz between the two oscillators, L/R detune in cents)
    for name, lev, beat, width in (("D1", -8.0, 0.05, 0.0), ("D2", 0.0, 0.08, 0.0),
                                   ("A2", -11.0, 0.11, 0.6), ("D3", -16.0, 0.0, 2.0)):
        f = hz(name)
        drift = 1.0 + 0.22 * np.clip(slow_noise(n, rng, 0.05), -2.5, 2.5)
        ph1, ph2 = rng.uniform(0.0, TWO_PI, 2)  # shared by L and R: the bass stays mono
        for ch in range(2):
            fc = f * 2.0 ** ((1 if ch else -1) * width / 1200.0)
            osc = np.sin(TWO_PI * fc * t + ph1) + 0.6 * np.sin(TWO_PI * (fc + beat) * t + ph2)
            out[:, ch] += db(lev) * drift * osc / 1.6
    water = bandpass_noise(n, rng, 35.0, 110.0, order=2, stereo=True)
    water = 0.75 * water.mean(axis=1, keepdims=True) + 0.25 * water  # mostly mono
    swell = 0.5 + 0.5 * np.tanh(1.2 * slow_noise(n, rng, 0.04))
    out += db(-20.0) * water * swell[:, None]
    out *= env[:, None]
    mx.add(out, 0.0, gain=LEVEL["drone"], dry=1.0, mid=0.12)


def breath_cue(rng: np.random.Generator) -> np.ndarray:
    """The point of light pulses: a round, bell-like D5 swell with no attack transient."""
    f0 = hz("D5")
    n = n_of(5.0)
    t = times(n)
    out = np.zeros(n)
    # ratio, amplitude, peak time, sharpness
    for r, a, tp, p in ((1.0, 1.0, 0.50, 2.4), (2.0, 0.30, 0.42, 2.6), (3.0, 0.09, 0.36, 2.8),
                        (4.16, 0.04, 0.30, 3.0), (0.5, 0.28, 0.56, 2.2)):
        out += a * gamma_env(t, tp, p) * np.sin(TWO_PI * f0 * r * t + rng.uniform(0.0, TWO_PI))
    out *= raised_cosine(n, 0, n_of(0.6))
    return out / np.max(np.abs(out))


def layer_breath_cues(mx: Mixer, rng: np.random.Generator) -> None:
    cue = breath_cue(rng)
    for t0 in (1.5, 98.7):  # identical sound, bookending the film
        mx.add(cue, t0, gain=LEVEL["breath"], dry=0.45, long=0.9)


def layer_glass(mx: Mixer, rng: np.random.Generator) -> None:
    """Glassy harmonics for the cell and the shoal."""
    pitches = ["D5", "F5", "A5", "C6", "D6", "E6", "F6", "A6"]
    weights = np.array([3.0, 1.5, 3.0, 1.0, 2.5, 1.0, 1.5, 2.0])
    weights /= weights.sum()
    t = 5.6
    while t < 40.0:
        f = hz(rng.choice(pitches, p=weights))
        dur = rng.uniform(4.0, 9.0)
        n = n_of(dur)
        tt = times(n)
        env = np.sin(np.pi * tt / dur) ** 1.6
        shimmer = np.ones(n)
        if 11.5 <= t < 19.5:  # shoal: slow tremolo
            trem = rng.uniform(0.3, 0.9)
            shimmer = 1.0 - 0.3 * (0.5 - 0.5 * np.cos(TWO_PI * trem * tt + rng.uniform(0.0, TWO_PI)))
        det = rng.uniform(2.0, 5.0)
        ph = rng.uniform(0.0, TWO_PI, 3)
        tone = (np.sin(TWO_PI * f * tt + ph[0])
                + 0.6 * np.sin(TWO_PI * f * 2.0 ** (det / 1200.0) * tt + ph[1])
                + 0.15 * env * np.sin(TWO_PI * 2.0 * f * tt + ph[2]))
        tone *= env * shimmer / 1.75
        level = db(rng.uniform(-6.0, 0.0)) * (hz("D5") / f) ** 0.5
        if t > 28.5:
            level *= max(0.0, 1.0 - (t - 28.5) / 11.5)  # thins out through the ape
        mx.add(tone * level, t, gain=LEVEL["glass"], dry=0.35, long=0.9, pan=rng.uniform(-0.5, 0.5))
        if t < 11.5:
            gap = rng.uniform(1.8, 3.2)
        elif t < 19.5:
            gap = rng.uniform(0.7, 1.5)
        elif t < 28.5:
            gap = rng.uniform(1.5, 3.0)
        else:
            gap = rng.uniform(3.0, 6.0)
        t += gap


def layer_sub_pulse(mx: Mixer, rng: np.random.Generator) -> None:
    """The light touches the floor: a soft sub pulse every 3.75 s (6 beats)."""
    f = hz("D2")
    n = n_of(3.0)
    t = times(n)
    env = gamma_env(t, 0.35, 2.0) * raised_cosine(n, 0, n_of(0.4))
    tone = (np.sin(TWO_PI * f * t) + 0.22 * np.sin(TWO_PI * 2.0 * f * t + 0.5)) * env
    tone /= np.max(np.abs(tone))
    k = 0
    while True:
        tt = GRID_T0 + 6 * BEAT * k
        if tt > 87.0:
            break
        taper = 1.0 if tt < 80.0 else max(0.0, 1.0 - (tt - 80.0) / 7.0)
        vel = rng.uniform(0.85, 1.0)
        mx.add(tone * vel * taper, tt + rng.uniform(-0.03, 0.03), gain=LEVEL["sub"], dry=1.0, mid=0.2)
        k += 1


def layer_wood(mx: Mixer, rng: np.random.Generator) -> None:
    """A single low wooden pulse: irregular with the ape, steady as she stands."""
    f = hz("D3")
    n = n_of(1.6)
    t = times(n)
    tone = np.zeros(n)
    for r, a, tau in ((1.0, 1.0, 0.55), (3.93, 0.28, 0.14), (9.1, 0.07, 0.05)):
        tone += a * np.exp(-t / tau) * np.sin(TWO_PI * f * r * t + rng.uniform(0.0, TWO_PI))
    nk = n_of(0.06)
    tone[:nk] += 0.12 * bandpass_noise(nk, rng, 250.0, 700.0) * np.hanning(nk)
    tone *= raised_cosine(n, n_of(0.02), n_of(0.25))
    tone /= np.max(np.abs(tone))
    steady = 3 * BEAT  # 1.875 s
    t0 = 28.5
    while t0 < 63.0:
        s = float(np.clip((t0 - 28.5) / 9.5, 0.0, 1.0))
        vel = (0.78 + 0.22 * rng.random()) * (1.0 - 0.25 * (1.0 - s))
        taper = 1.0 if t0 < 57.0 else max(0.0, 1.0 - (t0 - 57.0) / 6.0)
        mx.add(tone * vel * taper, t0, gain=LEVEL["wood"], dry=0.55, long=0.9)
        if s < 1.0:
            t_prev = t0
            t0 += (1.0 - s) * rng.uniform(1.1, 3.4) + s * steady
            if t0 >= 38.0:  # snap onto the nearest point of the shared grid from here on
                k = round((t0 - GRID_T0) / steady)
                t0 = GRID_T0 + (k + (1 if GRID_T0 + k * steady - t_prev < 1.0 else 0)) * steady
        else:
            t0 += steady


def _pad_voice(f0: float, n: int, rng: np.random.Generator, bright: np.ndarray, sign: int,
               n_partials: int = 8, rolloff: float = 1.3, detune_cents: float = 1.6,
               side: float = 0.6) -> np.ndarray:
    """One additive bowed/organ voice: a centred core plus quieter detuned L/R layers
    (gentle width that stays mono-compatible), per-partial amplitude drift, slow pitch wander."""
    wobble = 1.0 + 0.0012 * np.clip(slow_noise(n, rng, 0.25), -2.5, 2.5)
    base = TWO_PI * np.cumsum(f0 * wobble) / SR
    lr = (2.0 ** (-sign * detune_cents / 1200.0), 2.0 ** (sign * detune_cents / 1200.0))
    out = np.zeros((n, 2))
    for k in range(1, n_partials + 1):
        a = (1.0 / k ** rolloff) * (0.85 if k % 2 == 0 else 1.0)
        drift = 1.0 + 0.3 * np.clip(slow_noise(n, rng, 0.07), -2.5, 2.5)
        amp = a * drift * bright ** ((k - 1) / 3.0)
        ratio = k * 2.0 ** (rng.uniform(-0.4, 0.4) / 1200.0)
        ph = rng.uniform(0.0, TWO_PI)
        core = 0.75 * amp * np.sin(base * ratio + ph)
        for ch in range(2):
            out[:, ch] += core + side * amp * np.sin(base * ratio * lr[ch] + rng.uniform(0.0, TWO_PI))
    return out


def layer_pad(mx: Mixer, rng: np.random.Generator) -> None:
    """The bowed/organ pad: enters as she stands, swells once at the flame tip."""
    T0, T1 = 36.0, 93.0
    n = n_of(T1 - T0)
    master = db_env([(36.0, -90), (38.0, -60), (41.0, -10), (48.0, -10), (51.0, -6), (53.5, -1.5),
                     (55.0, 3), (55.5, 3), (56.5, -0.5), (58.0, -4.5), (59.5, -7.5), (61.5, -14),
                     (68.5, -14), (71.0, -11), (76.5, -10), (80.0, -8), (85.5, -8), (88.0, -11),
                     (90.5, -22), (92.0, -90)], n, T0)
    bright = breakpoints([(36.0, 0.6), (48.0, 0.6), (55.0, 0.85), (59.5, 0.5), (68.5, 0.55),
                          (76.5, 0.65), (82.0, 1.0), (85.5, 1.0), (92.0, 0.85)], n, T0)
    # (note, level dB, fade-in start, fade-in s, fade-out start, fade-out s)
    voices = [
        ("D3", 0.0, 38.0, 3.0, 88.0, 4.0),
        ("A3", -3.0, 38.5, 3.5, 50.0, 4.0),
        ("D4", -4.0, 39.0, 3.0, 88.0, 4.0),
        ("E4", -8.0, 40.0, 4.0, 50.0, 4.0),     # add9 — steps aside for the swell
        ("A4", -9.0, 41.0, 4.0, 88.0, 3.5),
        # the swell: Bb add9 over the D pedal (Bb D F A C)
        ("Bb2", -4.0, 49.0, 6.0, 55.5, 5.0),
        ("Bb3", -3.0, 49.5, 5.5, 55.5, 4.5),
        ("F4", -4.0, 50.0, 5.0, 55.5, 4.5),
        ("C5", -8.0, 51.0, 4.0, 55.5, 4.0),
        ("D5", -8.0, 51.5, 3.5, 55.5, 4.0),
        ("F5", -13.0, 52.5, 2.5, 55.5, 3.5),
        # afterwards
        ("A3", -3.0, 56.5, 4.0, 88.0, 4.0),
        ("E4", -9.0, 62.0, 4.0, 88.0, 4.0),
        ("F4", -8.0, 69.0, 4.0, 87.0, 4.0),     # furrows: minor colour
        ("D5", -11.0, 77.0, 4.0, 87.0, 3.5),    # city: pale gold
        ("E5", -14.0, 78.0, 4.0, 87.0, 3.5),
        ("A5", -17.0, 79.5, 4.0, 86.5, 3.5),
    ]
    pad = np.zeros((n, 2))
    for i, (note, lev, t_in, d_in, t_out, d_out) in enumerate(voices):
        s = n_of(t_in - T0)
        e = min(n, n_of(t_out + d_out - T0))
        m = e - s
        v = _pad_voice(hz(note), m, rng, bright[s:e], 1 if i % 2 == 0 else -1)
        v *= (db(lev) * raised_cosine(m, n_of(d_in), n_of(d_out)))[:, None]
        pad[s:e] += v
    pad /= np.max(np.abs(pad))
    pad = np.tanh(2.6 * pad) / math.tanh(2.6)  # soft saturation (warmer, denser at the swell)
    pad = lowpass(pad, 3200.0, 2)
    pad *= master[:, None]
    # the swell is mixed closer (less reverb) so it comes forward rather than washing out
    send = breakpoints([(36.0, 0.4), (52.0, 0.4), (54.0, 0.25), (56.5, 0.25), (59.0, 0.4), (93.0, 0.4)], n, T0)
    mx.add(pad, T0, gain=LEVEL["pad"], dry=0.85)
    mx.add(pad * send[:, None], T0, gain=LEVEL["pad"], dry=0.0, mid=1.0)


def layer_crackle(mx: Mixer, rng: np.random.Generator) -> None:
    """Soft pitched crackle under the fire hold (55.5–59.5) + a faint warm bed."""
    centers = [hz(p) for p in ("D6", "F6", "G6", "A6", "C7", "D7", "E7", "F7", "A7")]
    t = 55.5
    while True:
        u = (t - 55.5) / 4.0
        rate = 9.0 * (1.0 - u) + 2.5 * u
        t += rng.exponential(1.0 / rate)
        if t > 59.6:
            break
        if rng.random() < 0.18:  # a low pop
            fc = rng.uniform(150.0, 320.0)
            bw = fc * 0.5
            dur = rng.uniform(0.05, 0.1)
            amp = rng.uniform(0.5, 1.0) * 0.8
        else:
            fc = float(rng.choice(centers)) * 2.0 ** (rng.uniform(-15.0, 15.0) / 1200.0)
            bw = fc / 7.0
            dur = rng.uniform(0.045, 0.09)
            amp = rng.uniform(0.25, 1.0)
        ng = n_of(dur)
        g = bandpass_noise(ng, rng, fc - bw / 2.0, fc + bw / 2.0, order=2) * np.hanning(ng)
        g *= amp * (1.0 - 0.6 * min(1.0, max(0.0, (t - 55.5) / 4.0))) / (np.max(np.abs(g)) + 1e-9)
        mx.add(g, t, gain=LEVEL["crackle"], dry=0.6, mid=0.5, pan=rng.uniform(-0.6, 0.6))
    # warm low bed with slow flutter
    nb = n_of(5.0)
    bed = bandpass_noise(nb, rng, 90.0, 260.0, order=2, stereo=True)
    flutter = 1.0 + 0.35 * np.clip(slow_noise(nb, rng, 1.5), -2.0, 2.0)
    env = breakpoints([(55.3, 0.0), (56.0, 1.0), (58.5, 0.8), (60.3, 0.0)], nb, 55.3)
    bed *= (flutter * env)[:, None]
    mx.add(bed, 55.3, gain=LEVEL["fire_bed"], dry=0.7, mid=0.4)


def layer_breath_texture(mx: Mixer, rng: np.random.Generator) -> None:
    """Band-passed noise breathing slowly at the cave wall."""
    T0, T1 = 59.5, 75.0
    n = n_of(T1 - T0)
    lo = bandpass_noise(n, rng, 280.0, 520.0, order=2, stereo=True)
    hi = bandpass_noise(n, rng, 600.0, 1100.0, order=2, stereo=True)
    env_pts, mix_pts = [(T0, 0.0)], [(T0, 0.2)]
    for start, amp in ((60.0, 1.0), (65.0, 0.9), (69.8, 0.45)):
        env_pts += [(start, 0.0), (start + 2.2, amp), (start + 2.2 + 2.6, 0.0)]
        mix_pts += [(start, 0.2), (start + 2.2, 0.9), (start + 4.8, 0.3)]
    env_pts.append((T1, 0.0))
    mix_pts.append((T1, 0.2))
    env = breakpoints(env_pts, n, T0)
    w = breakpoints(mix_pts, n, T0)
    tex = (lo * (1.0 - w)[:, None] + hi * w[:, None]) * env[:, None]
    mx.add(tex, T0, gain=LEVEL["breath_tex"], dry=0.6, mid=0.6)


def layer_tick(mx: Mixer, rng: np.random.Generator) -> None:
    """A soft clock through long reverb under the furrows and the city."""
    def make(f: float) -> np.ndarray:
        nt = n_of(0.3)
        t = times(nt)
        body = (np.exp(-t / 0.045) * np.sin(TWO_PI * f * t)
                + 0.35 * np.exp(-t / 0.02) * np.sin(TWO_PI * 2.76 * f * t + 1.0))
        nc = n_of(0.03)
        body[:nc] += 0.3 * bandpass_noise(nc, rng, 1800.0, 5000.0) * np.hanning(nc)
        body *= raised_cosine(nt, n_of(0.02), n_of(0.1))
        return body / np.max(np.abs(body))

    sounds = (make(hz("A5")), make(hz("G5")))
    k0 = math.ceil((68.5 - GRID_T0) / BEAT)
    i = 0
    for k in range(k0, 10_000):
        t = GRID_T0 + k * BEAT
        if t > 91.0:
            break
        j = k - k0
        if t < 76.5:
            if j % 2:
                continue
            vel = 0.9
        else:
            vel = 1.0 if j % 2 == 0 else 0.6
            if j % 2 and rng.random() < 0.3:
                continue
        vel *= rng.uniform(0.85, 1.0)
        taper = 1.0 if t < 89.0 else max(0.0, 1.0 - (t - 89.0) / 3.0)
        snd = sounds[i % 2]
        mx.add(snd * vel * taper, t + rng.uniform(-0.004, 0.004), gain=LEVEL["tick"],
               dry=0.35, long=1.0, pan=-0.2 if i % 2 == 0 else 0.2)
        i += 1


def layer_high_tone(mx: Mixer, rng: np.random.Generator) -> None:
    """Everything empties to a single high, pure tone as the light spirals up."""
    T0, T1 = 90.5, 103.0
    n = n_of(T1 - T0)
    t = times(n, T0)
    env = breakpoints([(90.5, 0.0), (91.0, 0.0), (93.8, 1.0), (95.0, 1.0), (98.0, 0.5),
                       (101.5, 0.2), (103.0, 0.0)], n, T0)
    glide = breakpoints([(91.0, -20.0), (94.0, 0.0)], n, T0)
    depth = breakpoints([(91.0, 0.0), (93.0, 0.0), (97.0, 5.0), (103.0, 4.0)], n, T0)
    rate = 4.6 + 0.3 * slow_noise(n, rng, 0.2)
    vib = depth * np.sin(TWO_PI * np.cumsum(rate) / SR + rng.uniform(0.0, TWO_PI))
    f = hz("A6") * 2.0 ** ((glide + vib) / 1200.0)
    phase = TWO_PI * np.cumsum(f) / SR
    tone = np.sin(phase) + db(-14.0) * np.sin(0.5 * phase) + db(-34.0) * np.sin(3.0 * phase)
    tone *= env
    mx.add(tone, T0, gain=LEVEL["high"], dry=0.8, mid=0.4)


# ------------------------------------------------------------------------------ master
def soft_limiter(x: np.ndarray, threshold_db: float = LIMIT_DB, knee_db: float = 3.0,
                 release_s: float = 0.6, block: int = 48, lookahead_blocks: int = 3):
    """Gentle look-ahead soft-knee limiter with a 1 ms gain grid (transparent except at the swell)."""
    thr = db(threshold_db)
    n = len(x)
    nb = -(-n // block)
    padded = np.zeros((nb * block, x.shape[1]))
    padded[:n] = x
    pk = np.abs(padded).reshape(nb, block, x.shape[1]).max(axis=(1, 2))
    over = 20.0 * np.log10(np.maximum(pk, 1e-9) / thr)
    half = knee_db / 2.0
    gr = np.where(over <= -half, 0.0,
                  np.where(over < half, -(over + half) ** 2 / (2.0 * knee_db), -over))
    g_t = 10.0 ** (gr / 20.0)
    g_t = ndimage.minimum_filter1d(g_t, size=2 * lookahead_blocks + 1, mode="nearest")
    rel = math.exp(-block / (release_s * SR))
    g = np.empty(nb)
    prev = 1.0
    for i in range(nb):
        tgt = g_t[i]
        prev = tgt if tgt < prev else tgt + (prev - tgt) * rel
        g[i] = prev
    g = np.convolve(g, np.ones(3) / 3.0, mode="same")
    g[0], g[-1] = g[1], g[-2]
    gs = np.interp(np.arange(n), (np.arange(nb) + 0.5) * block, g)
    return x * gs[:, None], to_db(g.min())


def true_peak(x: np.ndarray, up: int = 4, chunk: int = 1 << 19, overlap: int = 256) -> float:
    peak = 0.0
    for s in range(0, len(x), chunk):
        a, b = max(0, s - overlap), min(len(x), s + chunk + overlap)
        y = signal.resample_poly(x[a:b], up, 1, axis=0)
        peak = max(peak, float(np.abs(y).max()))
    return peak


def render(seed: int = SEED, log=print) -> np.ndarray:
    """Render the whole score as float64 stereo (N, 2) in [-1, 1]."""
    t_start = time.time()
    rng = np.random.default_rng(seed)
    r = rng.spawn(12)
    mx = Mixer()
    for name, fn, child in (("drone", layer_drone, r[0]), ("breath cues", layer_breath_cues, r[1]),
                            ("glass", layer_glass, r[2]), ("sub pulse", layer_sub_pulse, r[3]),
                            ("wooden pulse", layer_wood, r[4]), ("pad", layer_pad, r[5]),
                            ("crackle", layer_crackle, r[6]), ("breath texture", layer_breath_texture, r[7]),
                            ("tick", layer_tick, r[8]), ("high tone", layer_high_tone, r[9])):
        t0 = time.time()
        fn(mx, child)
        log(f"  layer {name:<15s} {time.time() - t0:5.1f} s")

    t0 = time.time()
    ir_long = make_ir(r[10], rt60=4.6, predelay_ms=22.0, damp_hz=3200.0, hf_ratio=0.42)
    ir_mid = make_ir(r[11], rt60=2.8, predelay_ms=14.0, damp_hz=4500.0, hf_ratio=0.5)
    wet = (signal.fftconvolve(mx.long, ir_long, mode="full", axes=0)[:N]
           + signal.fftconvolve(mx.mid, ir_mid, mode="full", axes=0)[:N])
    mix = mx.dry + wet
    del wet, mx
    log(f"  reverb (2 buses)        {time.time() - t0:5.1f} s")

    # master: DC / rumble removal, film fades, loudness, limiter
    mix = signal.sosfiltfilt(sos_of("high", 25.0, 2), mix, axis=0)
    fade = np.ones(N)
    fade[:n_of(1.5)] = 0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, n_of(1.5)))
    n_out = N - n_of(101.5)
    fade[n_of(101.5):] = 0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, n_out))
    mix *= fade[:, None]
    # two passes so the integrated level is measured after the limiter
    for _ in range(2):
        rms = math.sqrt(float(np.mean(mix ** 2)))
        mix *= db(TARGET_RMS_DB) / rms
        mix, max_gr = soft_limiter(mix)
    tp = true_peak(mix)
    if tp > db(TRUE_PEAK_MAX_DB):
        mix *= db(TRUE_PEAK_MAX_DB) / tp
    mix[0] = 0.0
    mix[-1] = 0.0
    log(f"  limiter max gain reduction {max_gr:5.2f} dB; render total {time.time() - t_start:5.1f} s")
    return mix


def analyze(x: np.ndarray, sr: int = SR, log=print) -> dict:
    """Print duration, RMS, peaks, DC and a 10-second RMS table; return the numbers."""
    dur = len(x) / sr
    rms = math.sqrt(float(np.mean(x ** 2)))
    peak = float(np.abs(x).max())
    tp = true_peak(x)
    dc = x.mean(axis=0)
    log(f"duration      : {dur:.3f} s  ({len(x):,} samples/channel, {x.shape[1]} ch, {sr} Hz)")
    log(f"RMS (integr.) : {to_db(rms):6.2f} dBFS")
    log(f"sample peak   : {to_db(peak):6.2f} dBFS")
    log(f"true peak (4x): {to_db(tp):6.2f} dBFS")
    log(f"DC offset     : L {dc[0]:+.2e}  R {dc[1]:+.2e}")
    corr = float(np.corrcoef(x[:, 0], x[:, 1])[0, 1])
    log(f"L/R correlation: {corr:+.3f}  (mono-compatible if positive)")
    rows = []
    for s in range(0, int(math.ceil(dur)), 10):
        e = min(s + 10, dur)
        seg = x[int(s * sr):int(round(e * sr))]
        r = to_db(math.sqrt(float(np.mean(seg ** 2))))
        p = to_db(float(np.abs(seg).max()))
        rows.append((s, e, r, p))
    log("window (s)    RMS dBFS   peak dBFS")
    loud = max(rows, key=lambda r: r[2])
    for s, e, r, p in rows:
        bar = "#" * int(max(0.0, r + 60.0) / 1.5)
        flag = "  <- loudest" if (s, e) == loud[:2] else ""
        log(f"{s:5.0f}-{e:5.1f}   {r:7.2f}    {p:7.2f}   {bar}{flag}")
    end_seg = x[int(94.0 * sr):]
    end_rms = to_db(math.sqrt(float(np.mean(end_seg ** 2))))
    ranked = sorted(rows, key=lambda r: r[2])
    quiet_rank = [i for i, r in enumerate(ranked) if r[0] == 90][0] + 1 if any(r[0] == 90 for r in rows) else -1
    log(f"94.0-103.0 s RMS: {end_rms:7.2f} dBFS")
    log(f"arc check: loudest window = {loud[0]:.0f}-{loud[1]:.0f} s "
        f"({'OK' if loud[0] == 50 else 'NOT the 50-60 s window'}); "
        f"ending (90-100 s) is #{quiet_rank} quietest of {len(rows)} windows")
    return dict(duration=dur, rms_db=to_db(rms), peak_db=to_db(peak), true_peak_db=to_db(tp),
                dc=dc.tolist(), windows=rows, loudest=loud[:2], end_rms_db=end_rms)


def write_wav(path: str, x: np.ndarray, seed: int = SEED) -> np.ndarray:
    """Write 16-bit PCM with a hair of TPDF dither (gated so the ends stay digital silence)."""
    rng = np.random.default_rng(seed + 1)
    gate = (np.abs(x).max(axis=1) > 1e-6).astype(float)
    dither = (rng.random(x.shape) - rng.random(x.shape)) * gate[:, None]  # +-1 LSB TPDF
    y = np.clip(np.round(x * 32767.0 + dither), -32768, 32767).astype(np.int16)
    d = os.path.dirname(os.path.abspath(path))
    os.makedirs(d, exist_ok=True)
    wavfile.write(path, SR, y)
    return y


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Render the ONE LINE score (103.0 s, 48 kHz, 16-bit stereo).")
    ap.add_argument("--out", default="output/score.wav", help="output WAV path")
    ap.add_argument("--seed", type=int, default=SEED, help="random seed (default %(default)s)")
    args = ap.parse_args(argv)

    print(f"rendering ONE LINE score  seed={args.seed}  ({DURATION:.1f} s, {N:,} samples/ch)")
    mix = render(args.seed)
    write_wav(args.out, mix, args.seed)

    sr, data = wavfile.read(args.out)
    assert sr == SR and data.dtype == np.int16 and data.shape == (N, 2), (sr, data.dtype, data.shape)
    print(f"\nwrote {args.out}  ({os.path.getsize(args.out):,} bytes)\n")
    analyze(data.astype(np.float64) / 32768.0, sr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
