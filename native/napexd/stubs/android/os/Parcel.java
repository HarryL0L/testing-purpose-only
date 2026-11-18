package android.os; public final class Parcel {
  public static Parcel obtain() { return null; }
  public void recycle() {}
  public void enforceInterface(String s) {}
  public void writeInterfaceToken(String s) {}
  public void writeNoException() {}
  public void readException() {}
  public String readString() { return null; }
  public String[] createStringArray() { return null; }
  public void writeString(String s) {}
  public int readInt() { return 0; }
  public void writeInt(int v) {}
  public void writeLong(long v) {}
  public void writeBoolean(boolean v) {}
  public int dataPosition() { return 0; }
  public void setDataPosition(int p) {}
  public IBinder readStrongBinder() { return null; }
  public void writeStrongBinder(IBinder b) {}
  public void writeRawFileDescriptor(java.io.FileDescriptor fd) {}
}
