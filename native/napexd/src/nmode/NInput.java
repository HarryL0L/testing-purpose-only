package nmode;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * ninput: touches and keys from native mode's viewer, injected into Android
 * as the input manager's own events (as the `input` command does).
 *
 * The viewer connects to the abstract socket "ninput" and sends 16-byte
 * little-endian records: int kind, int pointer, int x, int y.
 *   kind 0/1/2/3  touch down / up / move / cancel at (x, y) in display pixels
 *   kind 4        key: x = Android key code, y = 1 down / 0 up
 * One touch pointer for now (pointer is ignored).
 *
 *   app_process -cp napexd.jar / nmode.NInput
 */
public final class NInput {
  private static final int SOURCE_TOUCHSCREEN = 0x1002;
  private static final int SOURCE_KEYBOARD = 0x101;
  private static final int INJECT_ASYNC = 0;  // InputEventInjectionSync.NONE

  private static Object sManager;
  private static Method sInject;

  public static void main(String[] args) throws Exception {
    // system_server's input service comes up during boot
    while (ServiceManager.checkService("input") == null) Thread.sleep(500);
    Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
    sManager = global.getMethod("getInstance").invoke(null);
    sInject = global.getMethod("injectInputEvent", InputEvent.class, int.class);
    LocalServerSocket server = new LocalServerSocket("ninput");
    System.err.println("ninput: listening");
    while (true) {
      LocalSocket s = server.accept();
      Thread t = new Thread(() -> serve(s), "ninput-client");
      t.start();
    }
  }

  private static int le(byte[] b, int off) {
    return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
  }

  private static void serve(LocalSocket s) {
    long downTime = 0;
    byte[] rec = new byte[16];
    try (LocalSocket sock = s) {
      DataInputStream in = new DataInputStream(sock.getInputStream());
      while (true) {
        in.readFully(rec);
        int kind = le(rec, 0), x = le(rec, 8), y = le(rec, 12);
        long now = SystemClock.uptimeMillis();
        if (kind <= 3) {
          int action = kind == 0 ? MotionEvent.ACTION_DOWN : kind == 1 ? MotionEvent.ACTION_UP
              : kind == 2 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_CANCEL;
          if (kind == 0) downTime = now;
          MotionEvent e = MotionEvent.obtain(downTime, now, action, x, y, 0);
          e.setSource(SOURCE_TOUCHSCREEN);
          inject(e);
          e.recycle();
        } else if (kind == 4) {
          KeyEvent e = new KeyEvent(now, now, y != 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, x, 0);
          e.setSource(SOURCE_KEYBOARD);
          inject(e);
        }
      }
    } catch (EOFException e) {
      // the viewer went away
    } catch (Exception e) {
      System.err.println("ninput: " + e);
    }
  }

  private static void inject(InputEvent e) {
    try {
      sInject.invoke(sManager, e, INJECT_ASYNC);
    } catch (Exception ex) {
      System.err.println("ninput: inject: " + ex);
    }
  }
}
