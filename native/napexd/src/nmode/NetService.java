package nmode;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The parts of netd every app talks to, which native mode doesn't run (netd
 * needs root to set up routes and firewalls; the network is the host app's,
 * used as is):
 *
 *   /dev/socket/fwmarkd   NetdClient asks it to mark each socket before
 *                         connect(); without it every connect() fails. Marks
 *                         mean nothing here: every request is granted.
 *   /dev/socket/dnsproxyd libc's getaddrinfo() (and so InetAddress) asks it;
 *                         answered from the phone's DNS servers, which the
 *                         host app writes to /data/local/tmp/ndns.
 *
 *   app_process -cp napexd.jar / nmode.NetService
 */
public final class NetService {
  private static final String DNS_FILE = "/data/local/tmp/ndns";
  private static final int AF_INET = 2, AF_INET6 = 10, AF_UNSPEC = 0;
  private static final int SOCK_STREAM = 1, SOCK_DGRAM = 2, IPPROTO_TCP = 6, IPPROTO_UDP = 17;
  private static final int AI_CANONNAME = 2;
  private static final int EAI_AGAIN = 2, EAI_FAIL = 4, EAI_NODATA = 7, EAI_SERVICE = 9;

  private static final Map<String, Cached> sCache = new HashMap<>();
  private static final Random sRandom = new Random();

  private static final class Cached {
    final List<InetAddress> addrs;
    final long until;

    Cached(List<InetAddress> a, long u) {
      addrs = a;
      until = u;
    }
  }

  public static void main(String[] args) throws Exception {
    LocalServerSocket fwmark = listen("/dev/socket/fwmarkd");
    LocalServerSocket dns = listen("/dev/socket/dnsproxyd");
    new Thread(() -> serve(fwmark, NetService::fwmark), "fwmarkd").start();
    System.err.println("netservice: listening");
    serve(dns, NetService::dnsproxy);
  }

  private interface Handler {
    void handle(LocalSocket s) throws IOException;
  }

  private static LocalServerSocket listen(String path) throws Exception {
    new File(path).delete();
    LocalSocket s = new LocalSocket(LocalSocket.SOCKET_STREAM);
    s.bind(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
    Os.chmod(path, 0666);
    return new LocalServerSocket(s.getFileDescriptor());
  }

  private static void serve(LocalServerSocket server, Handler h) {
    while (true) {
      try {
        LocalSocket s = server.accept();
        new Thread(() -> {
          try (LocalSocket c = s) {
            h.handle(c);
          } catch (Exception e) {
            // the client went away
          }
        }).start();
      } catch (IOException e) {
        System.err.println("netservice: accept: " + e);
      }
    }
  }

  // ------------------------------------------------------------- fwmarkd

  /** One FwmarkCommand (with the socket as SCM_RIGHTS) -> int 0. */
  private static void fwmark(LocalSocket s) throws IOException {
    byte[] buf = new byte[512];
    if (s.getInputStream().read(buf) <= 0) return;
    FileDescriptor[] fds = s.getAncillaryFileDescriptors();
    if (fds != null) {
      for (FileDescriptor fd : fds) {
        try {
          Os.close(fd);
        } catch (Exception ignored) {
        }
      }
    }
    s.getOutputStream().write(new byte[4]);  // error 0, native order
  }

  // ----------------------------------------------------------- dnsproxyd

  private static void dnsproxy(LocalSocket s) throws IOException {
    InputStream in = s.getInputStream();
    ByteArrayOutputStream cmd = new ByteArrayOutputStream();
    for (int b; (b = in.read()) > 0; ) cmd.write(b);
    List<String> argv = words(cmd.toString("UTF-8"));
    OutputStream out = s.getOutputStream();
    if (argv.size() >= 8 && argv.get(0).equals("getaddrinfo")) {
      getaddrinfo(argv, out);
    } else if (argv.size() >= 4 && argv.get(0).equals("gethostbyname")) {
      gethostbyname(nul(argv.get(2)), Integer.parseInt(argv.get(3)), out);
    } else {
      fail(out, EAI_FAIL);  // gethostbyaddr, resnsend, ...: not offered
    }
    out.flush();
  }

  /** FrameworkListener's words: spaces separate, backslash escapes, quotes group. */
  private static List<String> words(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder w = new StringBuilder();
    boolean quoted = false, any = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && i + 1 < line.length()) {
        w.append(line.charAt(++i));
        any = true;
      } else if (c == '"') {
        quoted = !quoted;
        any = true;
      } else if (c == ' ' && !quoted) {
        if (any) out.add(w.toString());
        w.setLength(0);
        any = false;
      } else {
        w.append(c);
        any = true;
      }
    }
    if (any) out.add(w.toString());
    return out;
  }

  /** netd's sendBinaryMsg(401, &error): code, big-endian length, native int. */
  private static void fail(OutputStream out, int error) throws IOException {
    out.write(new byte[] {'4', '0', '1', 0, 0, 0, 0, 4,
        (byte) error, (byte) (error >> 8), (byte) (error >> 16), (byte) (error >> 24)});
  }

  /** "gethostbyname <netid> <name> <af>": a hostent, as netd's sendhostent. */
  private static void gethostbyname(String host, int af, OutputStream out) throws IOException {
    List<InetAddress> addrs;
    try {
      addrs = resolve(host, af == AF_INET6 ? AF_INET6 : AF_INET);
    } catch (SocketTimeoutException e) {
      addrs = new ArrayList<>();
    }
    if (addrs.isEmpty()) {
      fail(out, 1);  // HOST_NOT_FOUND
      return;
    }
    DataOutputStream d = new DataOutputStream(out);
    d.write(new byte[] {'2', '2', '2', 0});
    byte[] n = ((host != null ? host : "") + "\0").getBytes("UTF-8");
    d.writeInt(n.length);
    d.write(n);
    d.writeInt(0);  // no aliases
    int len = addrs.get(0).getAddress().length;
    d.writeInt(len == 4 ? AF_INET : AF_INET6);
    d.writeInt(len);
    for (InetAddress ia : addrs) {
      d.writeInt(len);
      d.write(ia.getAddress());
    }
    d.writeInt(0);
  }

  private static void getaddrinfo(List<String> a, OutputStream out) throws IOException {
    String host = nul(a.get(1)), service = nul(a.get(2));
    int flags = Integer.parseInt(a.get(3)), family = Integer.parseInt(a.get(4));
    int socktype = Integer.parseInt(a.get(5)), protocol = Integer.parseInt(a.get(6));
    int port = 0;
    if (service != null) {
      try {
        port = Integer.parseInt(service);
      } catch (NumberFormatException e) {
        port = servicePort(service);
        if (port < 0) {
          fail(out, EAI_SERVICE);
          return;
        }
      }
    }
    List<InetAddress> addrs;
    try {
      addrs = resolve(host, family);
    } catch (SocketTimeoutException e) {
      fail(out, EAI_AGAIN);
      return;
    }
    if (addrs.isEmpty()) {
      fail(out, EAI_NODATA);
      return;
    }
    int[][] types = socktype != 0 ? new int[][] {{socktype, protocol}}
        : new int[][] {{SOCK_STREAM, IPPROTO_TCP}, {SOCK_DGRAM, IPPROTO_UDP}};
    DataOutputStream d = new DataOutputStream(out);
    d.write(new byte[] {'2', '2', '2', 0});
    boolean first = true;
    for (InetAddress ia : addrs) {
      for (int[] t : types) {
        d.writeInt(1);  // one more addrinfo
        d.writeInt(flags);
        d.writeInt(ia.getAddress().length == 4 ? AF_INET : AF_INET6);
        d.writeInt(t[0]);
        d.writeInt(t[1] != 0 ? t[1] : (t[0] == SOCK_DGRAM ? IPPROTO_UDP : IPPROTO_TCP));
        byte[] sa = sockaddr(ia, port);
        d.writeInt(sa.length);
        d.write(sa);
        if (first && (flags & AI_CANONNAME) != 0 && host != null) {
          byte[] n = (host + "\0").getBytes("UTF-8");
          d.writeInt(n.length);
          d.write(n);
        } else {
          d.writeInt(0);
        }
        first = false;
      }
    }
    d.writeInt(0);
  }

  private static String nul(String s) {
    return s.equals("^") ? null : s;
  }

  private static int servicePort(String s) {
    switch (s) {
      case "http": return 80;
      case "https": return 443;
      case "domain": return 53;
      case "ntp": return 123;
      case "ftp": return 21;
      case "ssh": return 22;
      case "smtp": return 25;
      case "imap": return 143;
      case "imaps": return 993;
      case "xmpp-client": return 5222;
      default: return -1;
    }
  }

  /** struct sockaddr_in / sockaddr_in6: family in host (little-endian) order. */
  private static byte[] sockaddr(InetAddress ia, int port) {
    byte[] ip = ia.getAddress();
    byte[] sa = new byte[ip.length == 4 ? 16 : 28];
    int af = ip.length == 4 ? AF_INET : AF_INET6;
    sa[0] = (byte) af;
    sa[1] = (byte) (af >> 8);
    sa[2] = (byte) (port >> 8);
    sa[3] = (byte) port;
    System.arraycopy(ip, 0, sa, ip.length == 4 ? 4 : 8, ip.length);
    return sa;
  }

  // ---------------------------------------------------------- resolving

  private static List<InetAddress> resolve(String host, int family) throws IOException {
    List<InetAddress> out = new ArrayList<>();
    if (host == null || host.equals("localhost") || host.equals("ip6-localhost")) {
      if (family != AF_INET6) out.add(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
      if (family != AF_INET) out.add(InetAddress.getByAddress(loopback6()));
      return out;
    }
    String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    // IPv4 first: the phone's network may not route IPv6
    boolean timedOut = false;
    for (int type : family == AF_INET ? new int[] {1} : family == AF_INET6 ? new int[] {28}
        : new int[] {1, 28}) {
      try {
        out.addAll(lookup(name, type));
      } catch (SocketTimeoutException e) {
        timedOut = true;
      }
    }
    if (out.isEmpty() && timedOut) throw new SocketTimeoutException(name);
    return out;
  }

  private static byte[] loopback6() {
    byte[] b = new byte[16];
    b[15] = 1;
    return b;
  }

  private static List<InetAddress> lookup(String name, int type) throws IOException {
    String key = type + " " + name.toLowerCase();
    long now = System.currentTimeMillis();
    synchronized (sCache) {
      Cached c = sCache.get(key);
      if (c != null && c.until > now) return c.addrs;
    }
    byte[] query = query(name, type);
    SocketTimeoutException timeout = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      for (InetAddress server : servers()) {
        FileDescriptor fd = null;
        try {
          // A socket of the server's own family: Java's are IPv6 ones,
          // which fail where the kernel or network has no IPv6.
          boolean v4 = server.getAddress().length == 4;
          fd = Os.socket(v4 ? OsConstants.AF_INET : OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, 0);
          Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
              StructTimeval.fromMillis(2500));
          Os.sendto(fd, query, 0, query.length, 0, server, 53);
          byte[] buf = new byte[4096];
          int n;
          while (true) {
            try {
              n = Os.recvfrom(fd, buf, 0, buf.length, 0, null);
            } catch (ErrnoException e) {
              if (e.errno == OsConstants.EAGAIN) throw new SocketTimeoutException(server.toString());
              throw new IOException(e);
            }
            if (n >= 12 && buf[0] == query[0] && buf[1] == query[1]) break;
          }
          int[] ttl = {3600};
          List<InetAddress> addrs = answers(buf, n, type, ttl);
          if (addrs != null) {
            synchronized (sCache) {
              sCache.put(key, new Cached(addrs, now + Math.max(30, Math.min(ttl[0], 600)) * 1000L));
            }
            return addrs;
          }
        } catch (SocketTimeoutException e) {
          timeout = e;
        } catch (IOException e) {
          // unreachable server: try the next
          System.err.println("netservice: " + server.getHostAddress() + ": " + e);
        } catch (ErrnoException e) {
          System.err.println("netservice: " + server.getHostAddress() + ": " + e);
        } finally {
          if (fd != null) {
            try {
              Os.close(fd);
            } catch (ErrnoException ignored) {
            }
          }
        }
      }
    }
    if (timeout != null) throw timeout;
    return new ArrayList<>();
  }

  /** The phone's DNS servers (written by the host app), then public ones. */
  private static List<InetAddress> servers() {
    List<InetAddress> out = new ArrayList<>();
    try {
      for (String l : Files.readAllLines(new File(DNS_FILE).toPath())) {
        l = l.trim();
        if (l.isEmpty() || l.contains("%")) continue;  // link-local: no scope here
        try {
          out.add(InetAddress.getByName(l));  // numeric: no lookup
        } catch (IOException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
    for (byte[] b : new byte[][] {{1, 1, 1, 1}, {8, 8, 8, 8}}) {
      try {
        out.add(InetAddress.getByAddress(b));
      } catch (IOException ignored) {
      }
    }
    return out;
  }

  private static byte[] query(String name, int type) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeShort(sRandom.nextInt(0x10000));
    d.writeShort(0x0100);  // recursion desired
    d.writeShort(1);
    d.writeShort(0);
    d.writeShort(0);
    d.writeShort(0);
    for (String label : name.split("\\.")) {
      byte[] l = label.getBytes("UTF-8");
      if (l.length == 0 || l.length > 63) throw new IOException("bad name " + name);
      d.writeByte(l.length);
      d.write(l);
    }
    d.writeByte(0);
    d.writeShort(type);
    d.writeShort(1);
    return b.toByteArray();
  }

  /** The addresses of `type` in a reply; null for a server failure (try another). */
  private static List<InetAddress> answers(byte[] m, int len, int type, int[] ttl)
      throws IOException {
    int rcode = m[3] & 0xf;
    List<InetAddress> out = new ArrayList<>();
    if (rcode == 3) return out;  // no such name
    if (rcode != 0) return null;
    int qd = u16(m, 4), an = u16(m, 6);
    int i = 12;
    for (int q = 0; q < qd; q++) i = skipName(m, i) + 4;
    for (int r = 0; r < an && i + 10 <= len; r++) {
      i = skipName(m, i);
      int t = u16(m, i), rdlen = u16(m, i + 8);
      long rttl = ((m[i + 4] & 0xffL) << 24) | ((m[i + 5] & 0xff) << 16) | ((m[i + 6] & 0xff) << 8)
          | (m[i + 7] & 0xff);
      i += 10;
      if (i + rdlen > len) break;
      if (t == type && ((t == 1 && rdlen == 4) || (t == 28 && rdlen == 16))) {
        byte[] ip = new byte[rdlen];
        System.arraycopy(m, i, ip, 0, rdlen);
        out.add(InetAddress.getByAddress(ip));
        ttl[0] = (int) Math.min(ttl[0], rttl);
      }
      i += rdlen;
    }
    return out;
  }

  private static int u16(byte[] m, int i) {
    return ((m[i] & 0xff) << 8) | (m[i + 1] & 0xff);
  }

  private static int skipName(byte[] m, int i) {
    while (i < m.length) {
      int l = m[i] & 0xff;
      if (l == 0) return i + 1;
      if ((l & 0xc0) == 0xc0) return i + 2;
      i += l + 1;
    }
    return i;
  }
}
