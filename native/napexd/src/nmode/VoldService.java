/*
 * vold's stand-in with the one thing apps need from it: user 0's shared
 * storage (/storage/emulated/0, the volume "emulated;0") reported mounted.
 *
 * Real vold creates that volume when a user starts, and mounting it hands
 * system_server a FUSE device for MediaProvider to serve. An app can't open
 * /dev/fuse, so MediaProvider gets one end of a socket pair instead: its FUSE
 * thread waits on it for good, and apps reach the files directly (paths are
 * plain directories here). Every other call is accepted with nothing to
 * report, as in StubService.
 */
package nmode;

import android.os.IBinder;
import android.os.Parcel;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;

public class VoldService extends StubService {
    // IVold
    private static final int SET_LISTENER = 1, RESET = 4, ON_USER_STARTED = 8, MOUNT = 15;
    // IVoldListener (all oneway)
    private static final String LISTENER = "android.os.IVoldListener";
    private static final int ON_VOLUME_CREATED = 5, ON_VOLUME_STATE_CHANGED = 6,
            ON_VOLUME_PATH_CHANGED = 8, ON_VOLUME_INTERNAL_PATH_CHANGED = 9;
    // IVoldMountCallback
    private static final String MOUNT_CALLBACK = "android.os.IVoldMountCallback";
    private static final int ON_VOLUME_CHECKING = 1;
    // VolumeInfo
    private static final int TYPE_EMULATED = 2, STATE_CHECKING = 1, STATE_MOUNTED = 2;

    private static final String PATH = "/storage/emulated", INTERNAL_PATH = "/data/media";

    private IBinder mListener;
    // The ends of the FUSE stand-ins nobody reads: kept so MediaProvider's end never sees EOF.
    private final java.util.List<FileDescriptor> mKeep = new java.util.ArrayList<>();

    VoldService() {
        super("android.os.IVold");
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code < 0x00ffffff) log("call " + code);
        switch (code) {
            case SET_LISTENER:
                data.enforceInterface(mDescriptor);
                mListener = data.readStrongBinder();
                break;
            case RESET:
                data.enforceInterface(mDescriptor);
                break;
            case ON_USER_STARTED: {
                data.enforceInterface(mDescriptor);
                int user = data.readInt();
                log("user " + user + " started: volume emulated;" + user);
                listener(ON_VOLUME_CREATED, "emulated;" + user, TYPE_EMULATED, "", "", user);
                break;
            }
            case MOUNT: {
                data.enforceInterface(mDescriptor);
                String id = data.readString();
                data.readInt();  // mount flags
                int user = data.readInt();
                IBinder callback = data.readStrongBinder();
                mount(id, user, callback);
                break;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
        if ((flags & IBinder.FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
        return true;
    }

    // VolumeBase::mount for an emulated volume, as vold reports it.
    private void mount(String id, int user, IBinder callback) {
        if (id == null || !id.startsWith("emulated")) return;
        listener(ON_VOLUME_STATE_CHANGED, id, STATE_CHECKING, user);
        listener(ON_VOLUME_INTERNAL_PATH_CHANGED, id, INTERNAL_PATH);
        listener(ON_VOLUME_PATH_CHANGED, id, PATH);
        boolean ok = true;
        if (callback != null) {
            FileDescriptor ours = new FileDescriptor(), theirs = new FileDescriptor();
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ours, theirs);
                mKeep.add(ours);
                fuseInit(ours);
                d.writeInterfaceToken(MOUNT_CALLBACK);
                d.writeRawFileDescriptor(theirs);
                d.writeString(PATH);
                d.writeString(INTERNAL_PATH);
                callback.transact(ON_VOLUME_CHECKING, d, r, 0);
                r.readException();
                ok = r.readInt() != 0;
            } catch (Exception e) {
                log("mount " + id + ": " + e);
                ok = false;
            } finally {
                d.recycle();
                r.recycle();
            }
        }
        log("mount " + id + (ok ? ": mounted" : ": system_server refused it"));
        if (ok) listener(ON_VOLUME_STATE_CHANGED, id, STATE_MOUNTED, user);
    }

    // The kernel's first FUSE request, which a FUSE server waits for before it
    // counts as started (MediaProvider waits for that): FUSE_INIT, protocol
    // 7.31, no features asked. Its reply, and anything after, is read and
    // dropped, on a thread of its own.
    private static void fuseInit(FileDescriptor fd) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(104).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(104).putInt(26).putLong(1).putLong(0);  // fuse_in_header: len, FUSE_INIT, unique, nodeid
        b.putInt(0).putInt(0).putInt(0).putInt(0);       // uid, gid, pid, extlen+padding
        b.putInt(7).putInt(31).putInt(128 * 1024).putInt(0);  // fuse_init_in: major, minor, max_readahead, flags
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256 * 1024];
            try {
                Os.write(fd, b.array(), 0, 104);
                while (Os.read(fd, buf, 0, buf.length) > 0) {
                    // dropped
                }
            } catch (Exception e) {
                log("FUSE stand-in: " + e);
            }
        }, "fuse-init");
        t.setDaemon(true);
        t.start();
    }

    // A oneway IVoldListener call; arguments are Strings or Integers, in order.
    private void listener(int code, Object... args) {
        if (mListener == null) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(LISTENER);
            for (Object a : args) {
                if (a instanceof Integer) d.writeInt((Integer) a); else d.writeString((String) a);
            }
            mListener.transact(code, d, null, IBinder.FLAG_ONEWAY);
        } catch (Exception e) {
            log("listener call " + code + ": " + e);
        } finally {
            d.recycle();
        }
    }

    // Its stdio is /dev/null (as from init): the log is a file of its own.
    private static void log(String s) {
        try (java.io.FileWriter w = new java.io.FileWriter("/data/local/tmp/vold.log", true)) {
            w.write(s + "\n");
        } catch (java.io.IOException e) {
            // nowhere to say it
        }
    }
}
