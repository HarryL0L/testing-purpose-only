/*
 * The software codecs' store, android.hardware.media.c2.IComponentStore/
 * software, as an empty one: no codecs. system_server asks for it at boot
 * (StorageManagerService checks for an HEVC decoder, on the thread that later
 * mounts storage) and waits forever if nobody answers. The real one,
 * mediaswcodec, can't run here: its seccomp filter has no room for the socket
 * calls our binder makes. Codec2Client insists on a store with an
 * IConfigurable, so there is one, named "software", with no parameters.
 */
package nmode;

import android.os.Parcel;

public class C2Store extends StubService {
    static final String STORE = "android.hardware.media.c2.IComponentStore";
    static final String CONFIGURABLE = "android.hardware.media.c2.IConfigurable";
    private static final int GET_INTERFACE_VERSION = 0x00fffffe;
    private static final int VERSION = 2;

    private final StubService mConfigurable = new Configurable();

    C2Store() {
        super(STORE);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        switch (code) {
            case 4:  // getConfigurable()
                data.enforceInterface(STORE);
                reply.writeNoException();
                reply.writeStrongBinder(mConfigurable);
                return true;
            case 7:  // listComponents(): none
                data.enforceInterface(STORE);
                reply.writeNoException();
                reply.writeInt(0);
                return true;
            case GET_INTERFACE_VERSION:
                data.enforceInterface(STORE);
                reply.writeNoException();
                reply.writeInt(VERSION);
                return true;
            case 0x5f4e5446:  // INTERFACE_TRANSACTION
            case 0x5f504e47:  // PING_TRANSACTION
                return super.onTransact(code, data, reply, flags);
            default:  // making components etc.: unknown transaction, an error to the caller
                return false;
        }
    }

    static class Configurable extends StubService {
        Configurable() {
            super(CONFIGURABLE);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            switch (code) {
                case 2:  // getId()
                    data.enforceInterface(CONFIGURABLE);
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case 3:  // getName()
                    data.enforceInterface(CONFIGURABLE);
                    reply.writeNoException();
                    reply.writeString("software");
                    return true;
                case 5:  // querySupportedParams(): none
                    data.enforceInterface(CONFIGURABLE);
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case GET_INTERFACE_VERSION:
                    data.enforceInterface(CONFIGURABLE);
                    reply.writeNoException();
                    reply.writeInt(VERSION);
                    return true;
                case 0x5f4e5446:
                case 0x5f504e47:
                    return super.onTransact(code, data, reply, flags);
                default:  // config, query: an error to the caller
                    return false;
            }
        }
    }
}
