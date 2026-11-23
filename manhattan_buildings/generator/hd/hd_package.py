"""HD kit: contact sheet + catalog.json.

usage: python3 hd_package.py <kit_dir>/hd
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
    hd = sys.argv[1]
    metas = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(hd, "meta", "*.json")))]
    with open(os.path.join(hd, "catalog.json"), "w") as f:
        json.dump(metas, f, indent=1)
    cols, W, H, lab = 6, 400, 500, 46
    rows = (len(metas) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * W, rows * (H + lab)), (22, 22, 24))
    d = ImageDraw.Draw(sheet)
    f1, f2 = font(19), font(15)
    for i, m in enumerate(metas):
        stem = os.path.splitext(os.path.basename(m["file"]))[0]
        p = os.path.join(hd, "previews", stem + ".jpg")
        x, y = (i % cols) * W, (i // cols) * (H + lab)
        if os.path.exists(p):
            sheet.paste(Image.open(p).convert("RGB").resize((W, H), Image.LANCZOS), (x, y))
        d.text((x + 6, y + H + 3), f"HD{m['id']:02d} {m['name']}"[:38], fill=(240, 240, 240), font=f1)
        d.text((x + 6, y + H + 25), f"{m['size_m'][2]:.0f} m · LOD0 {m['lod0_tris']:,} / LOD1 {m['lod1_tris']:,} tris",
               fill=(150, 150, 156), font=f2)
    out = os.path.join(hd, "contact_sheet_hd.jpg")
    sheet.save(out, quality=90)
    print(out, sheet.size)


if __name__ == "__main__":
    main()
