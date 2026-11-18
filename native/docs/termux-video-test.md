# Brief: test video playback in native mode on the phone (Termux)

For a Claude Code session running in Termux on the phone (RMX3031, Android 13,
Mali-G77). Goal: find why video plays with a black picture, no sound, and
system_server freezing (watchdog kill after ~67 s) in native mode, and report
the cause with evidence. Native speed and the real GPU matter here: the cloud
machine only has qemu and SwiftShader, where the video player never starts.

## Rules

- Touch nothing outside `~/android` and `~/UML`.
- Don't commit or push; report findings (and any patch as a diff) back.
- Use absolute paths with rm. No chained sleeps; use until-loops with timeouts.

## Setup

1. `cd ~/android/testing-purpose-only && git pull` (branch `native-android17`),
   or clone it there if missing.
2. Update the Termux root `~/android/a17` with the current runtime. Native mode
   runs Android 17 from it through `a17/run.sh`. Copy these from the repo's
   `native/app/app/src/main/assets/` into `~/android/a17/root/`:
   - `libc.so`, `libdl.so`, `libm.so` → `apex/com.android.runtime/lib64/bionic/`
   - `linker64` → `apex/com.android.runtime/bin/`
   - `napexd.jar` → `system/framework/`
   - `ncomposer`, `nbinder`, `nprops` → `system/bin/`
3. Apply what the app's `RuntimeService.patchNzygote` does to
   `~/android/a17/root/system/bin/nzygote`:
   - remove the cuttlefish health line;
   - start `mediaextractor`, `mediaswcodec`, `mediaserver`, `mediametrics` and
     `cameraserver` before `audioserver`;
   - start `nmode.NetService` before `nmode.StubService`.
   Read the Java for the exact lines.
4. Start Android 17 with `python3 ~/android/a17-mini/nstart.py a17` and the
   same properties the app passes:
   - `persist.zygote.app_data_isolation=false`
   - `persist.zygote.vold_app_data_isolation=false`
   - `persist.sys.timezone=<zone>`
   - `ro.input.video_enabled=false`
   - `native.display.width/height/dpi`
   See `RuntimeService.startAndroid`. Without the app's viewer, ncomposer
   writes frames to `/data/vendor/nfb`; `native/tools/nfb2png.py` turns one
   into a PNG.

## Test

1. Make a test clip, or use any small H.264 MP4, in
   `~/android/a17/root/storage/emulated/0/Movies/test.mp4`. If ffmpeg is
   available: an 8 s 320x240 testsrc2 with a 440 Hz AAC tone.
2. Play it:

   ```
   ./run.sh /system/bin/am start -a android.intent.action.VIEW \
     -d file:///storage/emulated/0/Movies/test.mp4 -t video/mp4 \
     -n com.android.gallery3d/.app.MovieActivity
   ```
3. Collect:
   - `root/data/local/tmp/log.txt` from just before the start to 90 s after;
   - frames via `nfb2png.py` at 5 s and 20 s;
   - whether system_server's watchdog fires;
   - Java stack dumps of the stuck threads (`kill -QUIT` the gallery and
     system_server pids, then look in `root/data/anr/`).

## Questions to answer

- Black picture: which buffer format does the decoder (CCodec, mediaswcodec)
  allocate for its output surface? Do `nalloc` / `mapper.native.so`
  (`native/gfx`) refuse it or mishandle it (YUV formats such as YCBCR_420_888,
  YV12, or the implementation-defined format)? Does SurfaceFlinger's GPU
  composition (PanVK, Vulkan) fail to sample it? Look for allocator,
  mapper, PanVK and SurfaceFlinger errors.
- No sound: what audioserver and the example audio HAL log (`AHAL_Stream`:
  "burst ... state ERROR"); where audio would need to go to reach the phone's
  speaker.
- Freeze: which lock or binder call blocks system_server's PowerManagerService
  and WindowManager during playback (watchdog stacks).

Report: the cause of each, with log lines and stacks, and a proposed fix.
