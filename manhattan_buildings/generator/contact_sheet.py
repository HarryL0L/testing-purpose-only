"""Assemble previews/*.png into a labelled contact sheet + catalog.json.

usage: python3 contact_sheet.py <kit_dir> [cols] [cell_px]
"""
import glob
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def main():
    kit = sys.argv[1]
    cols = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    S = int(sys.argv[3]) if len(sys.argv) > 3 else 240
    metas = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(kit, "meta", "*.json")))]
    with open(os.path.join(kit, "catalog.json"), "w") as f:
        json.dump(metas, f, indent=1)
    rows = (len(metas) + cols - 1) // cols
    lab = 34
    sheet = Image.new("RGB", (cols * S, rows * (S + lab)), (24, 24, 26))
    d = ImageDraw.Draw(sheet)
    f1, f2 = font(max(10, S // 16)), font(max(9, S // 20))
    for i, m in enumerate(metas):
        stem = os.path.splitext(os.path.basename(m["file"]))[0]
        p = os.path.join(kit, "previews", stem + ".jpg")
        x, y = (i % cols) * S, (i // cols) * (S + lab)
        if os.path.exists(p):
            sheet.paste(Image.open(p).convert("RGB").resize((S, S), Image.LANCZOS), (x, y))
        d.text((x + 4, y + S + 2), f"{m['id']:03d} {m['name']}"[:34], fill=(235, 235, 235), font=f1)
        d.text((x + 4, y + S + 2 + f1.size + 2), f"{m['size_m'][2]:.0f} m tall · {m['tris']} tris",
               fill=(150, 150, 155), font=f2)
    out = os.path.join(kit, "contact_sheet.jpg")
    sheet.save(out, quality=88)
    print(out, sheet.size)


if __name__ == "__main__":
    main()
