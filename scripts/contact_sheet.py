"""Grid of sampled frames for visual QA. usage: python3 scripts/contact_sheet.py --frames output/frames --n 24 --out sheet.png"""
import argparse, glob, os, math
import cv2, numpy as np

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default="output/frames")
    ap.add_argument("--n", type=int, default=24)
    ap.add_argument("--cols", type=int, default=4)
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--out", default="sheet.png")
    ap.add_argument("--tile_w", type=int, default=640)
    a = ap.parse_args()
    files = sorted(glob.glob(os.path.join(a.frames, "f*.png")))
    idx = np.linspace(0, len(files) - 1, a.n).round().astype(int)
    tiles = []
    th = int(a.tile_w * 9 / 16)
    for i in idx:
        img = cv2.imread(files[i])
        img = cv2.resize(img, (a.tile_w, th), interpolation=cv2.INTER_AREA)
        t = int(os.path.basename(files[i])[1:6]) / a.fps
        cv2.putText(img, f"{t:6.2f}s  #{int(os.path.basename(files[i])[1:6])}", (10, th - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1, cv2.LINE_AA)
        tiles.append(img)
    rows = math.ceil(len(tiles) / a.cols)
    sheet = np.zeros((rows * th, a.cols * a.tile_w, 3), np.uint8)
    for k, tile in enumerate(tiles):
        r, c = divmod(k, a.cols)
        sheet[r * th:(r + 1) * th, c * a.tile_w:(c + 1) * a.tile_w] = tile
    cv2.imwrite(a.out, sheet, [cv2.IMWRITE_PNG_COMPRESSION, 3])
    print(a.out, sheet.shape)

if __name__ == "__main__":
    main()
