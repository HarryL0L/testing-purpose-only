package io.github.harryl0l.a17;

import android.view.Surface;

/** The app's native half (libnviewer.so): display, input, processes, import. */
final class Native {
    static { System.loadLibrary("nviewer"); }

    // Display: draws ncomposer's frames (socket @ndisplay) into the surface.
    static native void viewerStart(Surface surface);
    static native void viewerStop();
    /** Android's display size once the viewer is connected: {width, height}, or null. */
    static native int[] displaySize();

    // Input: records for ninput (socket @ninput). kind 0..3 touch, 4 key.
    static native void input(int kind, int x, int y);

    // Processes: Android 17 from a17Dir (run.sh nboot, then run.sh nzygote),
    // this process adopting their orphans; stop kills whatever runs from the root.
    static native boolean start(String a17Dir, String[] props);
    static native int stop(String a17Dir);
    static native boolean running();

    // Import: a tar stream (Termux: tar --xattrs ... a17) read from fd into dir.
    static native String untar(int fd, String dir);
    static native long untarBytes();

    private Native() {}
}
