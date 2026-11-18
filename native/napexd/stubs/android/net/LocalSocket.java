package android.net;
public class LocalSocket implements java.io.Closeable {
  public static final int SOCKET_STREAM = 2;
  public LocalSocket() {}
  public LocalSocket(int type) {}
  public void bind(LocalSocketAddress a) throws java.io.IOException { throw new RuntimeException("stub"); }
  public java.io.FileDescriptor getFileDescriptor() { throw new RuntimeException("stub"); }
  public java.io.FileDescriptor[] getAncillaryFileDescriptors() throws java.io.IOException { throw new RuntimeException("stub"); }
  public java.io.InputStream getInputStream() throws java.io.IOException { throw new RuntimeException("stub"); }
  public java.io.OutputStream getOutputStream() throws java.io.IOException { throw new RuntimeException("stub"); }
  public void close() throws java.io.IOException {}
}
