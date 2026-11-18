package android.system; public final class Os {
  public static void socketpair(int domain, int type, int protocol, java.io.FileDescriptor fd1, java.io.FileDescriptor fd2) throws ErrnoException {}
  public static int write(java.io.FileDescriptor fd, byte[] b, int off, int n) throws ErrnoException, java.io.InterruptedIOException { return 0; }
  public static int read(java.io.FileDescriptor fd, byte[] b, int off, int n) throws ErrnoException, java.io.InterruptedIOException { return 0; }
  public static int fcntlInt(java.io.FileDescriptor fd, int cmd, int arg) throws ErrnoException { return 0; }
  public static void chmod(String path, int mode) throws ErrnoException {}
  public static void close(java.io.FileDescriptor fd) throws ErrnoException {}
  public static java.io.FileDescriptor socket(int domain, int type, int protocol) throws ErrnoException { return null; }
  public static int sendto(java.io.FileDescriptor fd, byte[] b, int off, int n, int flags, java.net.InetAddress a, int port) throws ErrnoException, java.net.SocketException { return 0; }
  public static int recvfrom(java.io.FileDescriptor fd, byte[] b, int off, int n, int flags, java.net.InetSocketAddress src) throws ErrnoException, java.net.SocketException { return 0; }
  public static void setsockoptTimeval(java.io.FileDescriptor fd, int level, int opt, StructTimeval v) throws ErrnoException {}
  public static void chown(String path, int uid, int gid) throws ErrnoException {}
}
