/*
 * napexd: the "apexservice" binder service for native mode.
 *
 * On a device apexd mounts every APEX and serves apexservice, which
 * system_server's ApexManager waits for at boot. In native mode a17root.py
 * unpacks the APEXes into /apex beforehand (an app can't mount), so this
 * stand-in only reports them: every APEX in /apex/apex-info-list.xml is
 * active and factory-installed, and there are never staged sessions.
 * Started by nzygote, before zygote:
 *   CLASSPATH=/system/framework/services.jar:/system/framework/napexd.jar \
 *       app_process64 /system/bin nmode.ApexService
 */
package nmode;

import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.apex.CompressedApexInfoList;
import android.apex.IApexService;
import android.os.Looper;
import android.os.ServiceManager;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApexService extends IApexService.Stub {
    private final ApexInfo[] mInfos;

    ApexService(ApexInfo[] infos) {
        mInfos = infos;
    }

    // Fields are set by name: the generated classes' field types (an AIDL
    // enum is a byte, ...) are whatever services.jar has.
    private static void set(Object o, String name, Object value) {
        try {
            Field f = o.getClass().getField(name);
            Class<?> t = f.getType();
            if (t == long.class) f.setLong(o, ((Number) value).longValue());
            else if (t == int.class) f.setInt(o, ((Number) value).intValue());
            else if (t == byte.class) f.setByte(o, ((Number) value).byteValue());
            else if (t == boolean.class) f.setBoolean(o, (Boolean) value);
            else f.set(o, value);
        } catch (ReflectiveOperationException e) {
            System.err.println("napexd: no field " + name + ": " + e);
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    static ApexInfo[] load() throws Exception {
        String xml = new String(Files.readAllBytes(new File("/apex/apex-info-list.xml").toPath()));
        List<ApexInfo> infos = new ArrayList<>();
        Matcher m = Pattern.compile("<apex-info\\b[^>]*>").matcher(xml);
        while (m.find()) {
            String tag = m.group();
            String name = attr(tag, "moduleName");
            ApexInfo info = new ApexInfo();
            set(info, "moduleName", name);
            set(info, "modulePath", attr(tag, "modulePath"));
            set(info, "preinstalledModulePath", attr(tag, "preinstalledModulePath"));
            String version = attr(tag, "versionCode");
            set(info, "versionCode", version.isEmpty() ? 0L : Long.parseLong(version));
            set(info, "versionName", attr(tag, "versionName"));
            set(info, "isFactory", true);
            set(info, "isActive", true);
            set(info, "hasClassPathJars", new File("/apex/" + name + "/etc/classpaths").isDirectory());
            set(info, "activeApexChanged", false);
            set(info, "partition", 0);  // SYSTEM
            infos.add(info);
        }
        return infos.toArray(new ApexInfo[0]);
    }

    @Override public ApexInfo[] getActivePackages() { return mInfos; }
    @Override public ApexInfo[] getAllPackages() { return mInfos; }
    @Override public ApexSessionInfo[] getSessions() { return new ApexSessionInfo[0]; }

    @Override public ApexSessionInfo getStagedSessionInfo(int sessionId) {
        ApexSessionInfo info = new ApexSessionInfo();
        set(info, "sessionId", sessionId);
        set(info, "isUnknown", true);
        return info;
    }

    @Override public ApexInfo[] getStagedApexInfos(ApexSessionParams params) { return new ApexInfo[0]; }
    @Override public void submitStagedSession(ApexSessionParams params, ApexInfoList packages) {
        throw new UnsupportedOperationException("napexd: no APEX updates in native mode");
    }
    @Override public void markStagedSessionReady(int sessionId) {}
    @Override public void markStagedSessionSuccessful(int sessionId) {}
    @Override public void abortStagedSession(int sessionId) {}
    @Override public void revertActiveSessions() {}
    @Override public void snapshotCeData(int userId, int rollbackId, String apexName) {}
    @Override public void restoreCeData(int userId, int rollbackId, String apexName) {}
    @Override public void destroyDeSnapshots(int rollbackId) {}
    @Override public void destroyCeSnapshots(int userId, int rollbackId) {}
    @Override public void destroyCeSnapshotsNotSpecified(int userId, int[] retain) {}
    @Override public void unstagePackages(List<String> paths) {}
    @Override public void stagePackages(List<String> paths) {}
    @Override public void resumeRevertIfNeeded() {}
    @Override public void recollectPreinstalledData() {}
    @Override public void markBootCompleted() {}
    @Override public long calculateSizeForCompressedApex(CompressedApexInfoList list) { return 0; }
    @Override public void reserveSpaceForCompressedApex(CompressedApexInfoList list) {}
    @Override public ApexInfo installAndActivatePackage(String path, boolean force) {
        throw new UnsupportedOperationException("napexd: no APEX updates in native mode");
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        ApexInfo[] infos = load();
        ServiceManager.addService("apexservice", new ApexService(infos));
        System.err.println("napexd: apexservice up with " + infos.length + " APEXes");
        Looper.loop();
    }
}
