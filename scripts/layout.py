"""Assign 3D positions/rotations to the storyboard shots along a gently curving walk.
usage: python3 scripts/layout.py --storyboard storyboard.json [--glyphs lightline/glyphs]"""
import argparse, json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from lightline.scene import auto_layout

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--storyboard", default="storyboard.json")
    ap.add_argument("--glyphs", default="lightline/glyphs")
    a = ap.parse_args()
    sb = json.load(open(a.storyboard))
    lay = sb.get("layout", {})
    auto_layout(sb, a.glyphs, turn_deg=lay.get("turn_deg", -8.0), gap_m=lay.get("gap_m", 3.0))
    json.dump(sb, open(a.storyboard, "w"), indent=1)
    for s in sb["shots"]:
        print(f"{s['id']:20s} pos {s['pos']}  rot_y {s['rot_y']}")

if __name__ == "__main__":
    main()
