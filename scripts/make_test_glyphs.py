"""procedural test glyphs used while developing the engine."""
import json, math, os
import numpy as np

out = os.path.join(os.path.dirname(__file__), "..", "lightline", "glyphs")

# DNA double helix: two strands + rungs
strands = []
for phase in (0.0, math.pi):
    pts = []
    for i in range(0, 161):
        s = i / 160
        a = 4 * math.pi * s + phase
        pts.append([0.28 * math.cos(a), 2.2 * s, 0.28 * math.sin(a)])
    strands.append(pts)
strokes = [{"pts": strands[0], "smooth": False}, {"pts": strands[1][::-1], "smooth": False}]
for j in range(1, 11):
    s = j / 11
    a = 4 * math.pi * s
    p = [0.28 * math.cos(a), 2.2 * s, 0.28 * math.sin(a)]
    q = [-0.28 * math.cos(a), 2.2 * s, -0.28 * math.sin(a)]
    strokes.append({"pts": [p, q] if j % 2 else [q, p], "smooth": False})
json.dump({"id": "test_helix", "strokes": strokes}, open(os.path.join(out, "test_helix.json"), "w"))

# simple upright human, side view: outline-ish strokes (control points, smoothed)
human = {
  "id": "test_human", "smooth": True,
  "strokes": [
    {"pts": [[0.0,1.72],[0.09,1.70],[0.13,1.62],[0.11,1.52],[0.04,1.47],[-0.05,1.50],[-0.09,1.59],[-0.06,1.68],[0.0,1.72]], "closed": True},
    {"pts": [[-0.02,1.46],[0.0,1.38],[0.08,1.30],[0.14,1.1],[0.12,0.9],[0.06,0.75]]},
    {"pts": [[0.0,1.38],[-0.10,1.30],[-0.16,1.1],[-0.14,0.92],[-0.08,0.78]]},
    {"pts": [[0.10,1.28],[0.2,1.05],[0.22,0.82]]},
    {"pts": [[-0.12,1.26],[-0.22,1.02],[-0.26,0.80]]},
    {"pts": [[0.06,0.75],[0.12,0.45],[0.16,0.12],[0.26,0.02]], "smooth": False},
    {"pts": [[-0.08,0.78],[-0.10,0.45],[-0.12,0.1],[-0.04,0.0]], "smooth": False},
  ]
}
json.dump(human, open(os.path.join(out, "test_human.json"), "w"))

# a simple fish, side view
fish = {
  "id": "test_fish", "smooth": True,
  "strokes": [
    {"pts": [[-0.9,0.0],[-0.5,0.32],[0.1,0.42],[0.6,0.3],[0.9,0.0],[0.6,-0.3],[0.1,-0.42],[-0.5,-0.32],[-0.9,0.0]], "closed": True},
    {"pts": [[-0.9,0.0],[-1.25,0.35],[-1.15,0.0],[-1.25,-0.35],[-0.9,0.0]]},
    {"pts": [[0.62,0.1],[0.7,0.14],[0.76,0.08],[0.7,0.02],[0.62,0.1]]},
    {"pts": [[0.55,0.28],[0.5,0.0],[0.55,-0.26]]},
  ]
}
json.dump(fish, open(os.path.join(out, "test_fish.json"), "w"))
print("wrote test glyphs")
