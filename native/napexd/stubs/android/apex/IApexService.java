package android.apex;
import java.util.List;
public interface IApexService extends android.os.IInterface {
  void submitStagedSession(ApexSessionParams params, ApexInfoList packages) throws android.os.RemoteException;
  void markStagedSessionReady(int session_id) throws android.os.RemoteException;
  void markStagedSessionSuccessful(int session_id) throws android.os.RemoteException;
  ApexSessionInfo[] getSessions() throws android.os.RemoteException;
  ApexSessionInfo getStagedSessionInfo(int session_id) throws android.os.RemoteException;
  ApexInfo[] getStagedApexInfos(ApexSessionParams params) throws android.os.RemoteException;
  ApexInfo[] getActivePackages() throws android.os.RemoteException;
  ApexInfo[] getAllPackages() throws android.os.RemoteException;
  void abortStagedSession(int session_id) throws android.os.RemoteException;
  void revertActiveSessions() throws android.os.RemoteException;
  void snapshotCeData(int user_id, int rollback_id, String apex_name) throws android.os.RemoteException;
  void restoreCeData(int user_id, int rollback_id, String apex_name) throws android.os.RemoteException;
  void destroyDeSnapshots(int rollback_id) throws android.os.RemoteException;
  void destroyCeSnapshots(int user_id, int rollback_id) throws android.os.RemoteException;
  void destroyCeSnapshotsNotSpecified(int user_id, int[] retain_rollback_ids) throws android.os.RemoteException;
  void unstagePackages(List<String> active_package_paths) throws android.os.RemoteException;
  void stagePackages(List<String> package_tmp_paths) throws android.os.RemoteException;
  void resumeRevertIfNeeded() throws android.os.RemoteException;
  void recollectPreinstalledData() throws android.os.RemoteException;
  void markBootCompleted() throws android.os.RemoteException;
  long calculateSizeForCompressedApex(CompressedApexInfoList compressed_apex_info_list) throws android.os.RemoteException;
  void reserveSpaceForCompressedApex(CompressedApexInfoList compressed_apex_info_list) throws android.os.RemoteException;
  ApexInfo installAndActivatePackage(String packagePath, boolean force) throws android.os.RemoteException;
  abstract class Stub extends android.os.Binder implements IApexService {
    public android.os.IBinder asBinder() { return this; }
  }
}
