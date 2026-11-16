/*
 * nvold: the "vold" binder service for native mode.
 *
 * vold manages storage: it mounts, encrypts and checkpoints, none of which an
 * app may do. system_server still calls it all through boot (checkpoints,
 * user storage, keyguard state, ...), so this stand-in accepts every call
 * as done, with nothing to report: each reply carries no exception and no
 * data, which the caller's proxy reads as false, 0, null or an empty array.
 * User storage is plain directories (a17root.py makes user 0's). Started by
 * nzygote, before zygote:
 *   CLASSPATH=/system/framework/napexd.jar app_process64 /system/bin nmode.VoldService
 */
package nmode;

import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;

public class VoldService extends Binder {
    private static final String DESCRIPTOR = "android.os.IVold";
    private static final int FIRST_CALL_TRANSACTION = 1;
    private static final int LAST_CALL_TRANSACTION = 0x00ffffff;
    private static final int FLAG_ONEWAY = 1;

    VoldService() {
        attachInterface(null, DESCRIPTOR);  // for INTERFACE_TRANSACTION (dumpsys, service list)
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
            try {
                return super.onTransact(code, data, reply, flags);  // ping, interface, dump
            } catch (Exception e) {
                return false;
            }
        }
        data.enforceInterface(DESCRIPTOR);
        if ((flags & FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
        return true;
    }

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        ServiceManager.addService("vold", new VoldService());
        System.err.println("nvold: vold up");
        Looper.loop();
    }
}
