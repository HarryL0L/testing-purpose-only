package io.github.harryl0l.a17;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Takes Android 17's root as a tar stream on 127.0.0.1:17017, so a root
 * built in Termux moves into the app without a copy on shared storage.
 */
final class Importer {
    static final int PORT = 17017;
    static final String INSTRUCTIONS =
            "Android 17 isn't installed in this app yet.\n\n"
            + "To move the one you set up in Termux into it, keep this screen open and run "
            + "in Termux:\n\n"
            + "cd ~/android && python3 a17-mini/nstart.py --stop a17 && "
            + "(tar -c --xattrs --xattrs-include='user.*' a17 | python3 -c \"import socket,sys,shutil; "
            + "s=socket.create_connection(('127.0.0.1'," + PORT + ")); "
            + "shutil.copyfileobj(sys.stdin.buffer, s.makefile('wb'), 1<<20)\" &)\n\n"
            + "then come back here. It takes a few minutes (about 3 GB); Android 17 "
            + "starts when it's done.";

    static void start(Context c, Consumer<String> status) {
        String dir = c.getFilesDir().getAbsolutePath();
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
                Socket s = server.accept();
                ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(s);
                Thread progress = new Thread(() -> {
                    try {
                        while (true) {
                            Thread.sleep(1000);
                            status.accept(String.format("Importing Android 17… %.0f MB",
                                    Native.untarBytes() / 1e6));
                        }
                    } catch (InterruptedException e) {
                        // done
                    }
                });
                progress.start();
                String err = Native.untar(pfd.detachFd(), dir);
                progress.interrupt();
                s.close();
                status.accept(err == null ? "Imported. Starting Android 17…"
                        : "Import failed: " + err + "\n\n" + INSTRUCTIONS);
            } catch (Exception e) {
                status.accept("Import failed: " + e + "\n\n" + INSTRUCTIONS);
            }
        }, "a17-import").start();
    }

    private Importer() {}
}
