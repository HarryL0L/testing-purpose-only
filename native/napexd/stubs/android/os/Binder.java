package android.os; public class Binder implements IBinder {
  public void attachInterface(IInterface owner, String descriptor) {}
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return false; }
}
