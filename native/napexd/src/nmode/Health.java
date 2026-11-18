/*
 * The battery HAL, android.hardware.health.IHealth/default, with the phone's
 * own battery. The image's (cuttlefish's) reports a fixed 85%, always
 * charging. The host app writes what its BatteryManager says to
 * /data/local/tmp/nbattery (key=value lines, BatteryManager's own numbers:
 * its status, health and plugged constants are the HAL's too); this reads it
 * every few seconds and tells system_server's callbacks when it changes.
 * Version 1 of the interface: system_server asks newer calls only of newer
 * versions.
 */
package nmode;

import android.os.IBinder;
import android.os.Parcel;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Health extends StubService {
    static final String NAME = "android.hardware.health.IHealth/default";
    private static final String DESCRIPTOR = "android.hardware.health.IHealth";
    private static final String CALLBACK = "android.hardware.health.IHealthInfoCallback";
    private static final String FILE = "/data/local/tmp/nbattery";
    private static final int GET_INTERFACE_VERSION = 0x00fffffe;
    private static final int GET_INTERFACE_HASH = 0x00fffffd;

    private final List<IBinder> mCallbacks = new ArrayList<>();
    private Map<String, Integer> mState = read();

    Health() {
        super(DESCRIPTOR);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                Map<String, Integer> now = read();
                boolean changed;
                synchronized (this) {
                    changed = !now.equals(mState);
                    mState = now;
                }
                if (changed) notifyCallbacks();
            }
        }, "nbattery");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Integer> read() {
        Map<String, Integer> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(FILE))) {
            for (String l; (l = r.readLine()) != null; ) {
                int eq = l.indexOf('=');
                if (eq > 0) {
                    try {
                        m.put(l.substring(0, eq), Integer.parseInt(l.substring(eq + 1).trim()));
                    } catch (NumberFormatException e) {
                        // skip it
                    }
                }
            }
        } catch (java.io.IOException e) {
            // not written yet: the defaults below
        }
        return m;
    }

    private synchronized int get(String key, int dflt) {
        Integer v = mState.get(key);
        return v != null ? v : dflt;
    }

    // HealthInfo (version 1), as a stable AIDL parcelable: its size, then the fields.
    private void writeHealthInfo(Parcel p) {
        int plugged = get("plugged", 0);
        int start = p.dataPosition();
        p.writeInt(0);
        p.writeBoolean((plugged & 1) != 0);   // chargerAcOnline
        p.writeBoolean((plugged & 2) != 0);   // chargerUsbOnline
        p.writeBoolean((plugged & 4) != 0);   // chargerWirelessOnline
        p.writeBoolean((plugged & 8) != 0);   // chargerDockOnline
        p.writeInt(0);                        // maxChargingCurrentMicroamps
        p.writeInt(0);                        // maxChargingVoltageMicrovolts
        p.writeInt(get("status", 1));         // batteryStatus (1: unknown)
        p.writeInt(get("health", 2));         // batteryHealth (2: good)
        p.writeBoolean(get("present", 1) != 0);
        p.writeInt(get("level", 50));
        p.writeInt(get("voltage", 3800));     // millivolts
        p.writeInt(get("temperature", 250));  // tenths of a degree
        p.writeInt(get("current_now", 0));
        p.writeInt(0);                        // batteryCycleCount
        p.writeInt(0);                        // batteryFullChargeUah
        p.writeInt(get("charge_counter", 0));
        p.writeString("Li-ion");
        p.writeInt(get("current_average", 0));
        p.writeInt(0);                        // diskStats: none
        p.writeInt(0);                        // storageInfos: none
        p.writeInt(-1);                       // batteryCapacityLevel: unsupported
        p.writeLong(-1);                      // batteryChargeTimeToFullNowSeconds: unsupported
        p.writeInt(0);                        // batteryFullChargeDesignCapacityUah
        int end = p.dataPosition();
        p.setDataPosition(start);
        p.writeInt(end - start);
        p.setDataPosition(end);
    }

    private void notifyCallbacks() {
        List<IBinder> cbs;
        synchronized (mCallbacks) {
            cbs = new ArrayList<>(mCallbacks);
        }
        for (IBinder cb : cbs) notify(cb);
    }

    private void notify(IBinder cb) {
        Parcel p = Parcel.obtain();
        try {
            p.writeInterfaceToken(CALLBACK);
            p.writeInt(1);
            writeHealthInfo(p);
            cb.transact(1, p, null, IBinder.FLAG_ONEWAY);  // healthInfoChanged
        } catch (Exception e) {
            synchronized (mCallbacks) {
                mCallbacks.remove(cb);
            }
        } finally {
            p.recycle();
        }
    }

    private void replyInt(Parcel reply, int v) {
        reply.writeNoException();
        reply.writeInt(v);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == 0x5f4e5446 || code == 0x5f504e47) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        switch (code) {
            case 1: {  // registerCallback
                IBinder cb = data.readStrongBinder();
                if (cb != null) {
                    synchronized (mCallbacks) {
                        mCallbacks.add(cb);
                    }
                    notify(cb);
                }
                reply.writeNoException();
                return true;
            }
            case 2: {  // unregisterCallback
                IBinder cb = data.readStrongBinder();
                synchronized (mCallbacks) {
                    mCallbacks.remove(cb);
                }
                reply.writeNoException();
                return true;
            }
            case 3:  // update
                synchronized (this) {
                    mState = read();
                }
                notifyCallbacks();
                reply.writeNoException();
                return true;
            case 4: replyInt(reply, get("charge_counter", 0)); return true;
            case 5: replyInt(reply, get("current_now", 0)); return true;
            case 6: replyInt(reply, get("current_average", 0)); return true;
            case 7: replyInt(reply, get("level", 50)); return true;
            case 8:  // getEnergyCounterNwh
                reply.writeNoException();
                reply.writeLong(0);
                return true;
            case 9: replyInt(reply, get("status", 1)); return true;
            case 10:  // getStorageInfo, getDiskStats: none
            case 11:
                replyInt(reply, 0);
                return true;
            case 12:  // getHealthInfo
                reply.writeNoException();
                reply.writeInt(1);
                writeHealthInfo(reply);
                return true;
            case GET_INTERFACE_VERSION:
                replyInt(reply, 1);
                return true;
            case GET_INTERFACE_HASH:
                reply.writeNoException();
                reply.writeString("notfrozen");
                return true;
            default:
                return false;
        }
    }
}
