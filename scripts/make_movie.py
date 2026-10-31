"""Encode rendered frames into the final video (H.264 + AAC), optionally muxing the score.

usage: python3 scripts/make_movie.py --frames output/frames --fps 24 --audio output/score.wav --out output/one_line.mp4
"""
import argparse, os, subprocess, sys, glob

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default="output/frames")
    ap.add_argument("--fps", type=int, default=24)
    ap.add_argument("--audio", default="output/score.wav")
    ap.add_argument("--out", default="output/one_line.mp4")
    ap.add_argument("--crf", type=int, default=15)
    ap.add_argument("--preset", default="slow")
    ap.add_argument("--maxrate", default="6500k", help="VBV cap; film grain otherwise drives the bitrate very high")
    a = ap.parse_args()
    files = sorted(glob.glob(os.path.join(a.frames, "f*.png")))
    if not files:
        sys.exit("no frames found")
    # verify the sequence is contiguous
    nums = [int(os.path.basename(f)[1:6]) for f in files]
    missing = sorted(set(range(nums[0], nums[-1] + 1)) - set(nums))
    if missing:
        sys.exit(f"missing frames: {missing[:20]}{'...' if len(missing) > 20 else ''}")
    bad = [f for f in files if os.path.getsize(f) == 0]
    if bad:
        sys.exit(f"empty frames: {bad[:10]}")
    print(f"{len(files)} frames ({nums[0]}..{nums[-1]}), {len(files)/a.fps:.2f} s")
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "warning", "-stats",
           "-framerate", str(a.fps), "-start_number", str(nums[0]), "-i", os.path.join(a.frames, "f%05d.png")]
    have_audio = a.audio and os.path.exists(a.audio)
    if have_audio:
        cmd += ["-i", a.audio]
    cmd += ["-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p",  # convert RGB->YUV with the 709 matrix the stream is tagged with
            "-c:v", "libx264", "-preset", a.preset, "-crf", str(a.crf), "-pix_fmt", "yuv420p",
            "-maxrate", a.maxrate, "-bufsize", str(int(a.maxrate.rstrip("k")) * 2) + "k",
            "-x264-params", "aq-mode=3:aq-strength=1.1:deblock=-1,-1:keyint=96:min-keyint=24",
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
            "-movflags", "+faststart"]
    if have_audio:
        cmd += ["-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-shortest"]
    cmd += [a.out]
    print(" ".join(cmd))
    subprocess.check_call(cmd)
    info = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration,size:stream=codec_name,width,height,r_frame_rate",
                           "-of", "default=noprint_wrappers=1", a.out], capture_output=True, text=True).stdout
    print(info)
    nb = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=nb_read_frames",
                         "-of", "default=noprint_wrappers=1:nokey=1", a.out], capture_output=True, text=True).stdout.strip()
    print(f"encoded video frames: {nb} (expected {len(files)})")
    if nb.isdigit() and int(nb) != len(files):
        sys.exit("frame count mismatch: the encode is truncated")

if __name__ == "__main__":
    main()
