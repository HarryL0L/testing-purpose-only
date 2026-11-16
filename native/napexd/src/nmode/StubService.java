/*
 * Stand-ins for system daemons an app can't run, for native mode:
 *
 *   vold   (android.os.IVold)          mounts, encrypts, checkpoints storage
 *   netd   (android.net.INetd)         firewall, routes, interfaces (needs root)
 *
 * system_server still calls them all through boot. Each stand-in accepts
 * every call as done, with nothing to report: the reply carries no exception
 * and no data, which the caller's proxy reads as false, 0, null or an empty
 * array. (Storage is plain directories, a17root.py makes user 0's; the
 * network is the host app's, used as is.) One process serves them all:
 *   CLASSPATH=/system/framework/napexd.jar app_process64 /system/bin \
 *       nmode.StubService vold=android.os.IVold netd=android.net.INetd
 */
package nmode;

import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;

public class StubService extends Binder {
    private static final int FIRST_CALL_TRANSACTION = 1;
    private static final int LAST_CALL_TRANSACTION = 0x00ffffff;
    private static final int FLAG_ONEWAY = 1;

    private final String mDescriptor;

    StubService(String descriptor) {
        mDescriptor = descriptor;
        attachInterface(null, descriptor);  // for INTERFACE_TRANSACTION (dumpsys, service list)
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
        data.enforceInterface(mDescriptor);
        if ((flags & FLAG_ONEWAY) == 0 && reply != null) reply.writeNoException();
        return true;
    }

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String name = arg.substring(0, eq), descriptor = arg.substring(eq + 1);
            ServiceManager.addService(name, new StubService(descriptor));
            System.err.println("nmode: stand-in " + name + " (" + descriptor + ") up");
        }
        Looper.loop();
    }
}
