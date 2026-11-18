/*
 * Stand-ins for system daemons an app can't run, for native mode:
 *
 *   vold   (android.os.IVold)          mounts, encrypts, checkpoints storage
 *   netd   (android.net.INetd)         firewall, routes, interfaces (needs root)
 *   dnsresolver (android.net.IDnsResolver)  netd's resolver configuration
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

    protected final String mDescriptor;

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
            // vold: user 0's shared storage reported mounted (VoldService)
            StubService s = name.equals("vold") ? new VoldService() : new StubService(descriptor);
            ServiceManager.addService(name, s);
            System.err.println("nmode: stand-in " + name + " (" + descriptor + ") up");
        }
        // The software codecs' store (mediaswcodec), which system_server and
        // MediaProvider wait for by name, forever: on the thread storage needs
        // too, in system_server. mediaswcodec can't run here (its seccomp filter
        // has no room for our socket binder), so C2Store answers instead, with
        // no codecs. (Not a command line argument: older boot scripts get it too.)
        ServiceManager.addService(C2Store.STORE + "/software", new C2Store());
        // The battery HAL with the phone's battery (Health): the image's reports
        // a fixed one. nzygote no longer starts that one (the app patches it out).
        ServiceManager.addService(Health.NAME, new Health());
        Looper.loop();
    }
}
