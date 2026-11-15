/*
 * nbinder: a userspace binder driver for native mode.
 *
 * An app can't have a binder context of its own from the kernel, so programs
 * from ANDROID_NATIVE_ROOT reach this daemon instead (libc's native_binder.cpp
 * turns open/ioctl/mmap on /dev/binder into messages on /dev/socket/nbinder;
 * see nbinder_proto.h). It keeps what the kernel driver keeps (processes,
 * threads, nodes, references, transaction stacks, buffers, death
 * notifications) and follows drivers/android/binder.c: the same commands and
 * returns, the same reference counting and BR_INCREFS/ACQUIRE/RELEASE/DECREFS
 * traffic, nested calls going back to the waiting thread, oneway calls
 * serialized per node. Transaction data is written straight into the
 * receiving process's buffer (a memfd it shares with us); fds travel as
 * SCM_RIGHTS and the receiving libc writes their numbers into the buffer.
 *
 * Not supported: scatter-gather buffers and fd arrays (BINDER_TYPE_PTR/FDA,
 * used by HIDL), freezing, priority inheritance.
 *
 *   nbinder [-f] [-v] [-s SOCKET]
 */

#include <errno.h>
#include <stddef.h>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <deque>
#include <iterator>
#include <list>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "nbinder_proto.h"

namespace {

bool g_verbose = false;

// When a process's SELinux context can't be read (no SELinux on the host).
constexpr char kFallbackSecctx[] = "u:r:untrusted_app:s0";

// Freeze notifications (newer than some uapi headers; the values are fixed).
constexpr uint32_t kBcRequestFreezeNotification = _IOW('c', 19, struct binder_handle_cookie);
constexpr uint32_t kBcClearFreezeNotification = _IOW('c', 20, struct binder_handle_cookie);
constexpr uint32_t kBcFreezeNotificationDone = _IOW('c', 21, binder_uintptr_t);

void nb_log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void nb_log(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "nbinder: ");
  vfprintf(stderr, fmt, ap);
  fprintf(stderr, "\n");
  va_end(ap);
}
#define VLOG(...) \
  do {            \
    if (g_verbose) nb_log(__VA_ARGS__); \
  } while (0)

size_t align8(size_t n) {
  return (n + 7) & ~static_cast<size_t>(7);
}

struct Proc;
struct Thread;
struct Node;
struct Ref;
struct Transaction;
struct Buffer;
struct Death;

enum WorkType { W_TRANSACTION, W_TCOMPLETE, W_ERROR, W_NODE, W_DEATH };

struct Work {
  WorkType type;
  bool cancelled = false;
  Transaction* t = nullptr;
  Node* node = nullptr;
  Death* death = nullptr;
  uint32_t cmd = 0;
};

typedef std::deque<Work*> WorkList;

// Death notification state, as binder_ref_death and its work type.
enum DeathType { D_DEAD_BINDER, D_DEAD_BINDER_AND_CLEAR, D_CLEAR };
struct Death {
  Proc* proc;
  Ref* ref;  // nullptr once the reference is gone
  uint64_t cookie;
  DeathType type = D_DEAD_BINDER;
  Work* queued = nullptr;  // pending work, if queued
  bool delivered = false;  // on proc->delivered_death
};

struct Node {
  uint64_t debug_id;
  Proc* proc;  // nullptr: owner died
  uint64_t ptr, cookie;
  bool accept_fds;
  bool txn_security_ctx;
  int internal_strong_refs = 0;
  int local_strong_refs = 0;
  int local_weak_refs = 0;
  std::set<Ref*> refs;
  bool has_strong_ref = false, pending_strong_ref = false;
  bool has_weak_ref = false, pending_weak_ref = false;
  bool has_async_transaction = false;
  WorkList async_todo;
  Work* queued = nullptr;  // node work, if queued
};

struct Ref {
  Proc* proc;
  Node* node;
  uint32_t desc;
  int strong = 0;
  int weak = 0;
  Death* death = nullptr;
};

struct Obj {  // a translated object in a buffer, for releasing it
  uint32_t type;
  Node* node;
  uint32_t desc;
};

struct Buffer {
  size_t off, size;
  size_t data_size, offsets_size;
  bool async;
  bool allow_user_free = false;
  Transaction* t = nullptr;
  Node* target_node = nullptr;
  std::vector<Obj> objs;
};

struct Transaction {
  uint64_t debug_id;
  Thread* from = nullptr;
  int from_pid = 0;
  uid_t sender_euid = 0;
  Proc* to_proc = nullptr;
  Thread* to_thread = nullptr;
  Transaction* from_parent = nullptr;
  Transaction* to_parent = nullptr;
  bool need_reply = false;
  bool is_reply = false;
  uint32_t code = 0, flags = 0;
  Buffer* buffer = nullptr;
  bool has_secctx = false;
  size_t secctx_off = 0;
  std::vector<int> fds;
  std::vector<uint64_t> fd_fixups;
};

enum : uint32_t {
  LOOPER_REGISTERED = 1,
  LOOPER_ENTERED = 2,
  LOOPER_EXITED = 4,
};

struct Conn;

struct Thread {
  Proc* proc;
  int tid;
  Conn* conn;
  uint32_t looper = 0;
  bool polling = false;
  bool need_return = true;
  bool dead = false;
  WorkList todo;
  Transaction* stack = nullptr;
  // A BINDER_WRITE_READ waiting for work.
  bool waiting = false;
  uint64_t pending_read_size = 0;
  uint32_t pending_write_cmds = 0;
};

struct Proc {
  uint64_t id;
  int pid;
  uid_t uid;
  std::string device;
  std::string secctx;
  Conn* main = nullptr;
  uint8_t* map = nullptr;
  size_t map_size = 0;
  uint64_t user_base = 0;
  size_t async_free = 0;
  std::map<size_t, Buffer*> buffers;  // by offset
  std::map<uint64_t, Node*> nodes;
  std::map<uint32_t, Ref*> refs_by_desc;
  std::map<Node*, Ref*> refs_by_node;
  std::map<int, Thread*> threads;
  WorkList todo;
  std::list<Death*> delivered_death;
  uint32_t max_threads = 0;
  uint32_t requested_threads = 0;
  uint32_t requested_threads_started = 0;
  bool has_poller = false;
  bool wake_pending = false;
};

enum ConnType { C_NEW, C_MAIN, C_THREAD };

struct Conn {
  int fd;
  ConnType type = C_NEW;
  Proc* proc = nullptr;
  Thread* thread = nullptr;
  std::vector<uint8_t> in;
  std::deque<int> fds;
  bool broken = false;
  uid_t uid = 0;
  pid_t pid = 0;
};

std::map<int, Conn*> g_conns;
std::map<uint64_t, Proc*> g_procs;
std::map<std::string, Node*> g_ctx_mgr;  // device -> context manager node
std::set<Node*> g_dead_nodes;
uint64_t g_next_id = 1;
int g_epoll = -1;

// ---------------------------------------------------------------- utilities

bool send_all(Conn* c, const void* data, size_t len, const int* fds, size_t nfds) {
  if (c == nullptr || c->broken) return false;
  const char* p = static_cast<const char*>(data);
  bool first = true;
  while (len > 0) {
    struct iovec iov = {const_cast<char*>(p), len};
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int) * NB_MAX_FDS)];
    if (first && nfds > 0) {
      msg.msg_control = control;
      msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
      struct cmsghdr* cm = CMSG_FIRSTHDR(&msg);
      cm->cmsg_level = SOL_SOCKET;
      cm->cmsg_type = SCM_RIGHTS;
      cm->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
      memcpy(CMSG_DATA(cm), fds, sizeof(int) * nfds);
    }
    ssize_t n = sendmsg(c->fd, &msg, MSG_NOSIGNAL);
    if (n < 0) {
      if (errno == EINTR) continue;
      if (errno == EAGAIN) {
        struct pollfd pfd = {c->fd, POLLOUT, 0};
        if (poll(&pfd, 1, 10000) > 0) continue;
      }
      c->broken = true;
      return false;
    }
    first = false;
    p += n;
    len -= static_cast<size_t>(n);
  }
  return true;
}

bool reply(Conn* c, const void* a, size_t alen, const void* b = nullptr, size_t blen = 0,
           const int* fds = nullptr, size_t nfds = 0) {
  std::string msg;
  nb_hdr h = {0, static_cast<uint32_t>(alen + blen)};
  msg.append(reinterpret_cast<const char*>(&h), sizeof(h));
  msg.append(static_cast<const char*>(a), alen);
  if (blen) msg.append(static_cast<const char*>(b), blen);
  return send_all(c, msg.data(), msg.size(), fds, nfds);
}

bool reply_status(Conn* c, int error) {
  nb_reply_status st = {error, 0};
  return reply(c, &st, sizeof(st));
}

void enqueue(WorkList& list, Work* w) {
  list.push_back(w);
}

bool list_has_work(const WorkList& list) {
  for (Work* w : list) {
    if (!w->cancelled) return true;
  }
  return false;
}

Work* dequeue(WorkList& list) {
  while (!list.empty()) {
    Work* w = list.front();
    list.pop_front();
    if (!w->cancelled) return w;
    delete w;
  }
  return nullptr;
}

std::string read_secctx(int pid) {
  char path[64];
  snprintf(path, sizeof(path), "/proc/%d/attr/current", pid);
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd == -1) return "";
  char buf[256];
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  if (n <= 0) return "";
  buf[n] = '\0';
  std::string s(buf);
  while (!s.empty() && (s.back() == '\n' || s.back() == '\0')) s.pop_back();
  return s;
}

// ------------------------------------------------------------ nodes and refs

void free_node(Node* node) {
  if (node->queued) node->queued->cancelled = true;
  if (node->proc) {
    node->proc->nodes.erase(node->ptr);
  } else {
    g_dead_nodes.erase(node);
  }
  for (auto it = g_ctx_mgr.begin(); it != g_ctx_mgr.end();) {
    it = it->second == node ? g_ctx_mgr.erase(it) : std::next(it);
  }
  VLOG("node %llu freed", static_cast<unsigned long long>(node->debug_id));
  delete node;
}

void enqueue_node_work(Node* node, WorkList& list) {
  if (node->queued) return;
  Work* w = new Work{W_NODE};
  w->node = node;
  node->queued = w;
  enqueue(list, w);
}

// binder_dec_node_nilocked(); returns whether the node was freed.
bool dec_node(Node* node, bool strong, bool internal) {
  Proc* proc = node->proc;
  if (strong) {
    if (internal) {
      if (node->internal_strong_refs > 0) node->internal_strong_refs--;
    } else if (node->local_strong_refs > 0) {
      node->local_strong_refs--;
    }
    if (node->local_strong_refs || node->internal_strong_refs) return false;
  } else {
    if (!internal && node->local_weak_refs > 0) node->local_weak_refs--;
    if (node->local_weak_refs || !node->refs.empty()) return false;
  }
  if (proc && (node->has_strong_ref || node->has_weak_ref)) {
    enqueue_node_work(node, proc->todo);
  } else if (node->refs.empty() && !node->local_strong_refs && !node->local_weak_refs &&
             !node->internal_strong_refs) {
    free_node(node);
    return true;
  }
  return false;
}

// binder_inc_node_nilocked(). target_list: where BR_INCREFS/BR_ACQUIRE go.
void inc_node(Node* node, bool strong, bool internal, WorkList* target_list) {
  if (strong) {
    if (internal) {
      node->internal_strong_refs++;
    } else {
      node->local_strong_refs++;
    }
    if (!node->has_strong_ref && target_list) {
      if (node->queued) {
        node->queued->cancelled = true;
        node->queued = nullptr;
      }
      enqueue_node_work(node, *target_list);
    } else if (!node->has_strong_ref && !node->queued && node->proc) {
      enqueue_node_work(node, node->proc->todo);
    }
  } else {
    if (!internal) node->local_weak_refs++;
    if (!node->has_weak_ref && !node->queued && (target_list || node->proc)) {
      enqueue_node_work(node, target_list ? *target_list : node->proc->todo);
    }
  }
}

Node* new_node(Proc* proc, uint64_t ptr, uint64_t cookie, uint32_t flags) {
  Node* node = new Node;
  node->debug_id = g_next_id++;
  node->proc = proc;
  node->ptr = ptr;
  node->cookie = cookie;
  node->accept_fds = flags & FLAT_BINDER_FLAG_ACCEPTS_FDS;
  node->txn_security_ctx = flags & FLAT_BINDER_FLAG_TXN_SECURITY_CTX;
  proc->nodes[ptr] = node;
  VLOG("proc %d: new node %llu ptr=%llx", proc->pid, static_cast<unsigned long long>(node->debug_id),
       static_cast<unsigned long long>(ptr));
  return node;
}

void cleanup_death(Death* death) {
  if (death->queued) {
    death->queued->cancelled = true;
    death->queued = nullptr;
  }
  if (death->delivered) {
    death->proc->delivered_death.remove(death);
    death->delivered = false;
  }
  delete death;
}

// binder_cleanup_ref_olocked() + binder_free_ref().
void free_ref(Ref* ref) {
  Proc* proc = ref->proc;
  proc->refs_by_desc.erase(ref->desc);
  proc->refs_by_node.erase(ref->node);
  Node* node = ref->node;
  bool freed = false;
  if (ref->strong) freed = dec_node(node, true, true);
  if (!freed) {
    node->refs.erase(ref);
    dec_node(node, false, true);
  }
  if (ref->death) cleanup_death(ref->death);
  delete ref;
}

Ref* get_ref_for_node(Proc* proc, Node* node) {
  auto it = proc->refs_by_node.find(node);
  if (it != proc->refs_by_node.end()) return it->second;
  Ref* ref = new Ref;
  ref->proc = proc;
  ref->node = node;
  auto cm = g_ctx_mgr.find(proc->device);
  uint32_t desc = (cm != g_ctx_mgr.end() && cm->second == node) ? 0 : 1;
  for (auto& [d, r] : proc->refs_by_desc) {
    (void)r;
    if (d < desc) continue;
    if (d > desc) break;
    desc = d + 1;
  }
  ref->desc = desc;
  proc->refs_by_desc[desc] = ref;
  proc->refs_by_node[node] = ref;
  node->refs.insert(ref);
  return ref;
}

void inc_ref(Ref* ref, bool strong, WorkList* target_list) {
  if (strong) {
    if (ref->strong == 0) inc_node(ref->node, true, true, target_list);
    ref->strong++;
  } else {
    if (ref->weak == 0) inc_node(ref->node, false, true, target_list);
    ref->weak++;
  }
}

// Returns whether the ref was freed.
bool dec_ref(Ref* ref, bool strong) {
  if (strong) {
    if (ref->strong == 0) return false;
    ref->strong--;
    if (ref->strong == 0) dec_node(ref->node, true, true);
  } else {
    if (ref->weak == 0) return false;
    ref->weak--;
  }
  if (ref->strong == 0 && ref->weak == 0) {
    // The strong part was dropped above.
    Proc* proc = ref->proc;
    proc->refs_by_desc.erase(ref->desc);
    proc->refs_by_node.erase(ref->node);
    ref->node->refs.erase(ref);
    dec_node(ref->node, false, true);
    if (ref->death) cleanup_death(ref->death);
    delete ref;
    return true;
  }
  return false;
}

Ref* inc_ref_for_node(Proc* proc, Node* node, bool strong, WorkList* target_list) {
  Ref* ref = get_ref_for_node(proc, node);
  inc_ref(ref, strong, target_list);
  return ref;
}

// ------------------------------------------------------------------ buffers

Buffer* alloc_buffer(Proc* proc, size_t data_size, size_t offsets_size, size_t extra, bool async) {
  if (proc->map == nullptr) return nullptr;
  size_t size = align8(data_size) + align8(offsets_size) + align8(extra);
  if (size < 8) size = 8;
  if (async && size > proc->async_free) return nullptr;
  size_t pos = 0;
  for (auto& [off, b] : proc->buffers) {
    if (off - pos >= size) break;
    pos = align8(off + b->size);
  }
  if (pos + size > proc->map_size) return nullptr;
  Buffer* b = new Buffer;
  b->off = pos;
  b->size = size;
  b->data_size = data_size;
  b->offsets_size = offsets_size;
  b->async = async;
  if (async) proc->async_free -= size;
  proc->buffers[pos] = b;
  return b;
}

void transaction_free(Transaction* t);

// binder_transaction_buffer_release(): drop what translating the objects took.
void release_buffer_objects(Proc* proc, Buffer* b) {
  for (const Obj& o : b->objs) {
    switch (o.type) {
      case BINDER_TYPE_BINDER:
      case BINDER_TYPE_WEAK_BINDER:
        dec_node(o.node, o.type == BINDER_TYPE_BINDER, false);
        break;
      case BINDER_TYPE_HANDLE:
      case BINDER_TYPE_WEAK_HANDLE: {
        auto it = proc->refs_by_desc.find(o.desc);
        if (it != proc->refs_by_desc.end()) dec_ref(it->second, o.type == BINDER_TYPE_HANDLE);
        break;
      }
    }
  }
  b->objs.clear();
  if (b->target_node) {
    Node* n = b->target_node;
    b->target_node = nullptr;
    dec_node(n, true, false);
  }
}

void free_buffer(Proc* proc, Buffer* b) {
  if (b->t) {
    b->t->buffer = nullptr;
    b->t = nullptr;
  }
  release_buffer_objects(proc, b);
  if (b->async) proc->async_free += b->size;
  proc->buffers.erase(b->off);
  delete b;
}

// ------------------------------------------------------------- transactions

void close_fds(Transaction* t) {
  for (int fd : t->fds) close(fd);
  t->fds.clear();
}

void transaction_free(Transaction* t) {
  if (t->buffer) t->buffer->t = nullptr;
  close_fds(t);
  delete t;
}

void enqueue_error(Thread* th, uint32_t cmd) {
  Work* w = new Work{W_ERROR};
  w->cmd = cmd;
  enqueue(th->todo, w);
}

// binder_send_failed_reply()
void send_failed_reply(Transaction* t, uint32_t error) {
  while (t) {
    Thread* target = t->from;
    if (target && !target->dead) {
      if (target->stack == t) target->stack = t->from_parent;
      enqueue_error(target, error);
      transaction_free(t);
      return;
    }
    Transaction* next = t->from_parent;
    transaction_free(t);
    t = next;
  }
}

// binder_release_work()
void release_work(WorkList& list) {
  while (Work* w = dequeue(list)) {
    switch (w->type) {
      case W_TRANSACTION: {
        Transaction* t = w->t;
        if (!t->is_reply && !(t->flags & TF_ONE_WAY)) {
          send_failed_reply(t, BR_DEAD_REPLY);
        } else {
          transaction_free(t);
        }
        break;
      }
      case W_NODE:
        w->node->queued = nullptr;
        break;
      case W_DEATH:
        w->death->queued = nullptr;
        if (w->death->ref == nullptr) delete w->death;  // cleared: nobody else has it
        break;
      default:
        break;
    }
    delete w;
  }
}

// Translate the objects of a buffer just copied into target's memory.
// Returns 0 or the BR_ error for the sender.
uint32_t translate_objects(Thread* th, Proc* target, Buffer* b, Transaction* t,
                           Transaction* in_reply_to, std::deque<int>& fds) {
  Proc* proc = th->proc;
  uint8_t* data = target->map + b->off;
  const uint8_t* offs = data + align8(b->data_size);
  size_t last_end = 0;
  for (size_t i = 0; i + sizeof(binder_size_t) <= b->offsets_size; i += sizeof(binder_size_t)) {
    binder_size_t off;
    memcpy(&off, offs + i, sizeof(off));
    if (off % sizeof(uint32_t) || off < last_end ||
        off + sizeof(struct binder_object_header) > b->data_size) {
      nb_log("proc %d: bad object offset %llu", proc->pid, static_cast<unsigned long long>(off));
      return BR_FAILED_REPLY;
    }
    struct binder_object_header hdr;
    memcpy(&hdr, data + off, sizeof(hdr));
    switch (hdr.type) {
      case BINDER_TYPE_BINDER:
      case BINDER_TYPE_WEAK_BINDER: {
        if (off + sizeof(struct flat_binder_object) > b->data_size) return BR_FAILED_REPLY;
        struct flat_binder_object fp;
        memcpy(&fp, data + off, sizeof(fp));
        bool strong = hdr.type == BINDER_TYPE_BINDER;
        Node* node;
        auto it = proc->nodes.find(fp.binder);
        if (it != proc->nodes.end()) {
          node = it->second;
          if (node->cookie != fp.cookie) {
            nb_log("proc %d: node %llx cookie mismatch", proc->pid,
                 static_cast<unsigned long long>(fp.binder));
            return BR_FAILED_REPLY;
          }
        } else {
          node = new_node(proc, fp.binder, fp.cookie, fp.flags);
        }
        Ref* ref = inc_ref_for_node(target, node, strong, &th->todo);
        fp.hdr.type = strong ? BINDER_TYPE_HANDLE : BINDER_TYPE_WEAK_HANDLE;
        fp.binder = 0;
        fp.handle = ref->desc;
        fp.cookie = 0;
        memcpy(data + off, &fp, sizeof(fp));
        b->objs.push_back({fp.hdr.type, nullptr, ref->desc});
        last_end = off + sizeof(fp);
        break;
      }
      case BINDER_TYPE_HANDLE:
      case BINDER_TYPE_WEAK_HANDLE: {
        if (off + sizeof(struct flat_binder_object) > b->data_size) return BR_FAILED_REPLY;
        struct flat_binder_object fp;
        memcpy(&fp, data + off, sizeof(fp));
        bool strong = hdr.type == BINDER_TYPE_HANDLE;
        auto it = proc->refs_by_desc.find(fp.handle);
        if (it == proc->refs_by_desc.end()) {
          nb_log("proc %d: transaction with invalid handle %u", proc->pid, fp.handle);
          return BR_FAILED_REPLY;
        }
        Node* node = it->second->node;
        if (node->proc == target) {
          fp.hdr.type = strong ? BINDER_TYPE_BINDER : BINDER_TYPE_WEAK_BINDER;
          fp.binder = node->ptr;
          fp.cookie = node->cookie;
          inc_node(node, strong, false, nullptr);
          b->objs.push_back({fp.hdr.type, node, 0});
        } else {
          Ref* ref = inc_ref_for_node(target, node, strong, nullptr);
          fp.binder = 0;
          fp.handle = ref->desc;
          fp.cookie = 0;
          b->objs.push_back({fp.hdr.type, nullptr, ref->desc});
        }
        memcpy(data + off, &fp, sizeof(fp));
        last_end = off + sizeof(fp);
        break;
      }
      case BINDER_TYPE_FD: {
        if (off + sizeof(struct binder_fd_object) > b->data_size) return BR_FAILED_REPLY;
        bool accept = in_reply_to ? (in_reply_to->flags & TF_ACCEPT_FDS)
                                  : (b->target_node && b->target_node->accept_fds);
        if (!accept) {
          nb_log("proc %d: fd sent to a target that doesn't accept fds", proc->pid);
          return BR_FAILED_REPLY;
        }
        if (fds.empty()) {
          nb_log("proc %d: fd object without an fd", proc->pid);
          return BR_FAILED_REPLY;
        }
        struct binder_fd_object fo;
        memcpy(&fo, data + off, sizeof(fo));
        t->fds.push_back(fds.front());
        fds.pop_front();
        fo.pad_binder = 0;
        fo.fd = static_cast<__u32>(-1);  // the receiving libc writes the real number
        memcpy(data + off, &fo, sizeof(fo));
        t->fd_fixups.push_back(b->off + off + offsetof(struct binder_fd_object, fd));
        last_end = off + sizeof(fo);
        break;
      }
      default:
        nb_log("proc %d: unsupported object type %x", proc->pid, hdr.type);
        return BR_FAILED_REPLY;
    }
  }
  return 0;
}

Thread* any_waiting_for_proc_work(Proc* proc);

// binder_transaction()
void do_transaction(Thread* th, const struct binder_transaction_data& tr, const uint8_t* data,
                    const uint8_t* offsets, bool reply, std::deque<int>& fds) {
  Proc* proc = th->proc;
  Transaction* in_reply_to = nullptr;
  Thread* target_thread = nullptr;
  Proc* target_proc = nullptr;
  Node* target_node = nullptr;
  uint32_t error = 0;

  if (reply) {
    in_reply_to = th->stack;
    if (in_reply_to == nullptr || in_reply_to->to_thread != th) {
      nb_log("proc %d thread %d: reply with no transaction to reply to", proc->pid, th->tid);
      enqueue_error(th, BR_FAILED_REPLY);
      return;
    }
    th->stack = in_reply_to->to_parent;
    target_thread = in_reply_to->from;
    if (target_thread == nullptr || target_thread->dead) {
      // The caller is gone.
      transaction_free(in_reply_to);
      enqueue_error(th, BR_DEAD_REPLY);
      return;
    }
    if (target_thread->stack != in_reply_to) {
      nb_log("proc %d: reply target has a different transaction stack", proc->pid);
      enqueue_error(th, BR_TRANSACTION_COMPLETE);
      send_failed_reply(in_reply_to, BR_FAILED_REPLY);
      return;
    }
    target_proc = target_thread->proc;
  } else {
    if (tr.target.handle == 0) {
      auto it = g_ctx_mgr.find(proc->device);
      if (it == g_ctx_mgr.end()) {
        enqueue_error(th, BR_DEAD_REPLY);
        return;
      }
      target_node = it->second;
    } else {
      auto it = proc->refs_by_desc.find(tr.target.handle);
      if (it == proc->refs_by_desc.end()) {
        nb_log("proc %d: transaction to invalid handle %u", proc->pid, tr.target.handle);
        enqueue_error(th, BR_FAILED_REPLY);
        return;
      }
      target_node = it->second->node;
    }
    target_proc = target_node->proc;
    if (target_proc == nullptr) {
      enqueue_error(th, BR_DEAD_REPLY);
      return;
    }
    if (target_proc == proc) {
      nb_log("proc %d: transaction to itself", proc->pid);
      enqueue_error(th, BR_FAILED_REPLY);
      return;
    }
    if (!(tr.flags & TF_ONE_WAY) && th->stack) {
      Transaction* tmp = th->stack;
      if (tmp->to_thread != th) {
        nb_log("proc %d thread %d: bad transaction stack", proc->pid, th->tid);
        enqueue_error(th, BR_FAILED_REPLY);
        return;
      }
      for (; tmp; tmp = tmp->from_parent) {
        if (tmp->from && !tmp->from->dead && tmp->from->proc == target_proc) {
          target_thread = tmp->from;
        }
      }
    }
  }

  Transaction* t = new Transaction;
  t->debug_id = g_next_id++;
  t->from = (!reply && !(tr.flags & TF_ONE_WAY)) ? th : nullptr;
  t->from_pid = proc->pid;
  t->sender_euid = proc->uid;
  t->to_proc = target_proc;
  t->to_thread = target_thread;
  t->code = tr.code;
  t->flags = tr.flags;
  t->is_reply = reply;

  std::string secctx;
  if (target_node && target_node->txn_security_ctx) {
    // As the driver, always give one: servicemanager aborts without it.
    secctx = proc->secctx.empty() ? kFallbackSecctx : proc->secctx;
    secctx.push_back('\0');
  }
  bool async = !reply && (tr.flags & TF_ONE_WAY);
  Buffer* b = alloc_buffer(target_proc, tr.data_size, tr.offsets_size, secctx.size(), async);
  if (b == nullptr) {
    nb_log("proc %d -> %d: no buffer space (%llu bytes)", proc->pid, target_proc->pid,
         static_cast<unsigned long long>(tr.data_size + tr.offsets_size));
    delete t;
    error = BR_FAILED_REPLY;
  } else {
    t->buffer = b;
    b->t = t;
    b->target_node = target_node;
    if (target_node) inc_node(target_node, true, false, nullptr);
    uint8_t* dst = target_proc->map + b->off;
    if (tr.data_size) memcpy(dst, data, tr.data_size);
    if (tr.offsets_size) memcpy(dst + align8(tr.data_size), offsets, tr.offsets_size);
    if (!secctx.empty()) {
      t->has_secctx = true;
      t->secctx_off = b->off + align8(tr.data_size) + align8(tr.offsets_size);
      memcpy(target_proc->map + t->secctx_off, secctx.data(), secctx.size());
    }
    error = translate_objects(th, target_proc, b, t, in_reply_to, fds);
    if (error) {
      b->t = nullptr;
      free_buffer(target_proc, b);
      t->buffer = nullptr;
      transaction_free(t);
    }
  }

  if (error) {
    if (in_reply_to) {
      enqueue_error(th, BR_TRANSACTION_COMPLETE);
      send_failed_reply(in_reply_to, error);
    } else {
      enqueue_error(th, error);
    }
    return;
  }

  b->allow_user_free = true;
  Work* tcomplete = new Work{W_TCOMPLETE};
  enqueue(th->todo, tcomplete);
  Work* w = new Work{W_TRANSACTION};
  w->t = t;
  if (reply) {
    target_thread->stack = in_reply_to->from_parent;
    enqueue(target_thread->todo, w);
    transaction_free(in_reply_to);
  } else if (!(t->flags & TF_ONE_WAY)) {
    t->need_reply = true;
    t->from_parent = th->stack;
    th->stack = t;
    enqueue(target_thread ? target_thread->todo : target_proc->todo, w);
  } else {
    if (target_node->has_async_transaction) {
      enqueue(target_node->async_todo, w);
    } else {
      target_node->has_async_transaction = true;
      enqueue(target_proc->todo, w);
    }
  }
}

// ------------------------------------------------------------------ threads

void release_thread(Thread* th) {
  if (th->dead) return;
  Transaction* t = th->stack;
  Transaction* send_reply = (t && t->to_thread == th) ? t : nullptr;
  while (t) {
    if (t->to_thread == th) {
      t->to_proc = nullptr;
      t->to_thread = nullptr;
      if (t->buffer) {
        t->buffer->t = nullptr;
        t->buffer = nullptr;
      }
      t = t->to_parent;
    } else if (t->from == th) {
      t->from = nullptr;
      t = t->from_parent;
    } else {
      break;
    }
  }
  th->stack = nullptr;
  th->dead = true;
  th->waiting = false;
  if (send_reply) send_failed_reply(send_reply, BR_DEAD_REPLY);
  release_work(th->todo);
}

void delete_thread(Thread* th) {
  release_thread(th);
  th->proc->threads.erase(th->tid);
  if (th->conn) th->conn->thread = nullptr;
  delete th;
}

// ------------------------------------------------------------------- reads

bool thread_wants_proc_work(Thread* th) {
  return th->stack == nullptr && !list_has_work(th->todo);
}

Thread* any_waiting_for_proc_work(Proc* proc) {
  for (auto& [tid, th] : proc->threads) {
    (void)tid;
    if (th->waiting && thread_wants_proc_work(th)) return th;
  }
  return nullptr;
}

void put32(std::string& out, uint32_t v) {
  out.append(reinterpret_cast<const char*>(&v), sizeof(v));
}

template <typename T>
void put(std::string& out, const T& v) {
  out.append(reinterpret_cast<const char*>(&v), sizeof(v));
}

// binder_thread_read(), without blocking: fills `out` with what this thread
// can get now. Returns whether anything besides BR_NOOP was produced.
bool fill_read(Thread* th, size_t read_size, std::string& out, std::vector<int>& fds,
               std::vector<uint64_t>& fixups) {
  Proc* proc = th->proc;
  if (read_size < 4) return false;
  put32(out, BR_NOOP);
  while (true) {
    bool want_proc = thread_wants_proc_work(th);
    WorkList* list = nullptr;
    if (list_has_work(th->todo)) {
      list = &th->todo;
    } else if (want_proc && list_has_work(proc->todo)) {
      list = &proc->todo;
    } else {
      break;
    }
    if (read_size - out.size() < sizeof(struct binder_transaction_data_secctx) + 4 + 8 ||
        read_size - out.size() < 4 * (4 + sizeof(struct binder_ptr_cookie))) {
      break;
    }
    Work* w = dequeue(*list);
    bool stop = false;
    switch (w->type) {
      case W_TCOMPLETE:
        put32(out, BR_TRANSACTION_COMPLETE);
        break;
      case W_ERROR:
        put32(out, w->cmd);
        break;
      case W_NODE: {
        Node* node = w->node;
        node->queued = nullptr;
        bool strong = node->internal_strong_refs || node->local_strong_refs;
        bool weak = !node->refs.empty() || node->local_weak_refs || strong;
        bool has_weak = node->has_weak_ref, has_strong = node->has_strong_ref;
        struct binder_ptr_cookie pc = {node->ptr, node->cookie};
        if (weak && !has_weak) {
          node->has_weak_ref = true;
          node->pending_weak_ref = true;
          node->local_weak_refs++;
        }
        if (strong && !has_strong) {
          node->has_strong_ref = true;
          node->pending_strong_ref = true;
          node->local_strong_refs++;
        }
        if (!strong && has_strong) node->has_strong_ref = false;
        if (!weak && has_weak) node->has_weak_ref = false;
        if (weak && !has_weak) {
          put32(out, BR_INCREFS);
          put(out, pc);
        }
        if (strong && !has_strong) {
          put32(out, BR_ACQUIRE);
          put(out, pc);
        }
        if (!strong && has_strong) {
          put32(out, BR_RELEASE);
          put(out, pc);
        }
        if (!weak && has_weak) {
          put32(out, BR_DECREFS);
          put(out, pc);
        }
        if (!weak && !strong) free_node(node);
        break;
      }
      case W_DEATH: {
        Death* death = w->death;
        death->queued = nullptr;
        if (death->type == D_CLEAR) {
          put32(out, BR_CLEAR_DEATH_NOTIFICATION_DONE);
          put(out, static_cast<binder_uintptr_t>(death->cookie));
          delete death;
        } else {
          put32(out, BR_DEAD_BINDER);
          put(out, static_cast<binder_uintptr_t>(death->cookie));
          death->delivered = true;
          proc->delivered_death.push_back(death);
          stop = true;  // DEAD_BINDER notifications can cause transactions
        }
        break;
      }
      case W_TRANSACTION: {
        Transaction* t = w->t;
        Buffer* b = t->buffer;
        struct binder_transaction_data_secctx trs = {};
        struct binder_transaction_data& tr = trs.transaction_data;
        uint32_t cmd;
        if (!t->is_reply && b && b->target_node) {
          tr.target.ptr = b->target_node->ptr;
          tr.cookie = b->target_node->cookie;
          cmd = t->has_secctx ? BR_TRANSACTION_SEC_CTX : BR_TRANSACTION;
        } else {
          tr.target.ptr = 0;
          tr.cookie = 0;
          cmd = BR_REPLY;
        }
        tr.code = t->code;
        tr.flags = t->flags;
        tr.sender_pid = t->from_pid;
        tr.sender_euid = t->sender_euid;
        if (b) {
          tr.data_size = b->data_size;
          tr.offsets_size = b->offsets_size;
          tr.data.ptr.buffer = proc->user_base + b->off;
          tr.data.ptr.offsets = proc->user_base + b->off + align8(b->data_size);
        }
        if (t->has_secctx) trs.secctx = proc->user_base + t->secctx_off;
        put32(out, cmd);
        if (cmd == BR_TRANSACTION_SEC_CTX) {
          put(out, trs);
        } else {
          put(out, tr);
        }
        for (size_t i = 0; i < t->fds.size(); i++) {
          fds.push_back(t->fds[i]);
          fixups.push_back(t->fd_fixups[i]);
        }
        t->fds.clear();
        if (!t->is_reply && !(t->flags & TF_ONE_WAY)) {
          t->to_parent = th->stack;
          t->to_thread = th;
          th->stack = t;
        } else {
          transaction_free(t);
        }
        stop = true;
        break;
      }
    }
    delete w;
    if (stop) break;
  }
  bool produced = out.size() > 4;
  if (produced && proc->requested_threads == 0 && any_waiting_for_proc_work(proc) == nullptr &&
      proc->requested_threads_started < proc->max_threads &&
      (th->looper & (LOOPER_REGISTERED | LOOPER_ENTERED))) {
    proc->requested_threads++;
    uint32_t spawn = BR_SPAWN_LOOPER;
    memcpy(&out[0], &spawn, sizeof(spawn));
  }
  return produced;
}

// Answer this thread's pending BINDER_WRITE_READ if it can be answered now.
bool try_complete_read(Thread* th, int32_t error = 0) {
  std::string out;
  std::vector<int> fds;
  std::vector<uint64_t> fixups;
  bool produced = false;
  if (error == 0 && th->pending_read_size > 0) {
    produced = fill_read(th, th->pending_read_size, out, fds, fixups);
    bool may_return_empty =
        th->need_return || (th->polling && th->stack == nullptr) || th->pending_read_size < 8;
    if (!produced && !may_return_empty) {
      th->waiting = true;
      return false;
    }
  }
  th->waiting = false;
  th->need_return = false;
  nb_write_read_reply rep = {error, th->pending_write_cmds, out.size(),
                             static_cast<uint32_t>(fixups.size()), 0};
  std::string payload = out;
  payload.append(reinterpret_cast<const char*>(fixups.data()), fixups.size() * sizeof(uint64_t));
  reply(th->conn, &rep, sizeof(rep), payload.data(), payload.size(), fds.data(), fds.size());
  for (int fd : fds) close(fd);
  return true;
}

// ------------------------------------------------------------------- writes

void enqueue_death(Thread* th, Death* death) {
  Work* w = new Work{W_DEATH};
  w->death = death;
  death->queued = w;
  if (th->looper & (LOOPER_REGISTERED | LOOPER_ENTERED)) {
    enqueue(th->todo, w);
  } else {
    enqueue(th->proc->todo, w);
  }
}

// binder_thread_write(). Returns 0 or an errno; *consumed counts commands.
int thread_write(Thread* th, const uint8_t* buf, size_t len, std::deque<int>& fds,
                 uint32_t* consumed, bool reading) {
  Proc* proc = th->proc;
  size_t pos = 0;
  *consumed = 0;
  while (pos + 4 <= len) {
    uint32_t cmd;
    memcpy(&cmd, buf + pos, 4);
    size_t argsz = _IOC_SIZE(cmd);
    if (pos + 4 + argsz > len) return EINVAL;
    const uint8_t* arg = buf + pos + 4;
    size_t next = pos + 4 + argsz;
    switch (cmd) {
      case BC_INCREFS:
      case BC_ACQUIRE:
      case BC_RELEASE:
      case BC_DECREFS: {
        uint32_t target;
        memcpy(&target, arg, 4);
        bool strong = cmd == BC_ACQUIRE || cmd == BC_RELEASE;
        bool increment = cmd == BC_INCREFS || cmd == BC_ACQUIRE;
        Ref* ref = nullptr;
        auto cm = g_ctx_mgr.find(proc->device);
        if (target == 0 && increment && cm != g_ctx_mgr.end() && cm->second->proc != proc) {
          ref = get_ref_for_node(proc, cm->second);
          if (ref->desc != 0) nb_log("proc %d: context manager ref has desc %u", proc->pid, ref->desc);
        } else {
          auto it = proc->refs_by_desc.find(target);
          if (it != proc->refs_by_desc.end()) ref = it->second;
        }
        if (ref == nullptr) {
          nb_log("proc %d: refcount change on invalid ref %u", proc->pid, target);
          break;
        }
        if (increment) {
          inc_ref(ref, strong, nullptr);
        } else {
          dec_ref(ref, strong);
        }
        break;
      }
      case BC_INCREFS_DONE:
      case BC_ACQUIRE_DONE: {
        struct binder_ptr_cookie pc;
        memcpy(&pc, arg, sizeof(pc));
        auto it = proc->nodes.find(pc.ptr);
        if (it == proc->nodes.end() || it->second->cookie != pc.cookie) {
          nb_log("proc %d: %s for unknown node %llx", proc->pid,
               cmd == BC_INCREFS_DONE ? "BC_INCREFS_DONE" : "BC_ACQUIRE_DONE",
               static_cast<unsigned long long>(pc.ptr));
          break;
        }
        Node* node = it->second;
        if (cmd == BC_ACQUIRE_DONE) {
          node->pending_strong_ref = false;
        } else {
          node->pending_weak_ref = false;
        }
        dec_node(node, cmd == BC_ACQUIRE_DONE, false);
        break;
      }
      case BC_FREE_BUFFER: {
        binder_uintptr_t ptr;
        memcpy(&ptr, arg, sizeof(ptr));
        auto it = ptr >= proc->user_base ? proc->buffers.find(ptr - proc->user_base)
                                         : proc->buffers.end();
        if (it == proc->buffers.end() || !it->second->allow_user_free) {
          nb_log("proc %d: BC_FREE_BUFFER of unknown buffer %llx", proc->pid,
               static_cast<unsigned long long>(ptr));
          break;
        }
        Buffer* b = it->second;
        if (b->async && b->target_node) {
          Node* node = b->target_node;
          Work* w = dequeue(node->async_todo);
          if (w == nullptr) {
            node->has_async_transaction = false;
          } else {
            enqueue(proc->todo, w);
          }
        }
        free_buffer(proc, b);
        break;
      }
      case BC_TRANSACTION:
      case BC_REPLY:
      case BC_TRANSACTION_SG:
      case BC_REPLY_SG: {
        struct binder_transaction_data tr;
        memcpy(&tr, arg, sizeof(tr));
        size_t dlen = align8(tr.data_size), olen = align8(tr.offsets_size);
        if (next + dlen + olen > len || tr.offsets_size % sizeof(binder_size_t)) return EINVAL;
        const uint8_t* data = buf + next;
        const uint8_t* offsets = data + dlen;
        next += dlen + olen;
        if ((cmd == BC_TRANSACTION_SG || cmd == BC_REPLY_SG)) {
          struct binder_transaction_data_sg sg;
          memcpy(&sg, arg, sizeof(sg));
          if (sg.buffers_size) {
            nb_log("proc %d: scatter-gather transactions are not supported", proc->pid);
            enqueue_error(th, BR_FAILED_REPLY);
            break;
          }
        }
        do_transaction(th, tr, data, offsets, cmd == BC_REPLY || cmd == BC_REPLY_SG, fds);
        break;
      }
      case BC_REGISTER_LOOPER:
        if (proc->requested_threads > 0) {
          proc->requested_threads--;
          proc->requested_threads_started++;
        }
        th->looper |= LOOPER_REGISTERED;
        break;
      case BC_ENTER_LOOPER:
        th->looper |= LOOPER_ENTERED;
        // IPCThreadState::setupPolling() sends this alone, without reading:
        // this thread waits in poll() rather than in the driver.
        if (!reading) {
          th->polling = true;
          proc->has_poller = true;
        }
        break;
      case BC_EXIT_LOOPER:
        th->looper |= LOOPER_EXITED;
        break;
      case BC_REQUEST_DEATH_NOTIFICATION:
      case BC_CLEAR_DEATH_NOTIFICATION: {
        struct binder_handle_cookie hc;
        memcpy(&hc, arg, sizeof(hc));
        auto it = proc->refs_by_desc.find(hc.handle);
        if (it == proc->refs_by_desc.end()) {
          nb_log("proc %d: death notification for invalid handle %u", proc->pid, hc.handle);
          break;
        }
        Ref* ref = it->second;
        if (cmd == BC_REQUEST_DEATH_NOTIFICATION) {
          if (ref->death) break;
          Death* death = new Death{proc, ref, hc.cookie};
          ref->death = death;
          if (ref->node->proc == nullptr) enqueue_death(th, death);
        } else {
          Death* death = ref->death;
          if (death == nullptr || death->cookie != hc.cookie) break;
          ref->death = nullptr;
          death->ref = nullptr;
          if (death->queued == nullptr && !death->delivered) {
            death->type = D_CLEAR;
            enqueue_death(th, death);
          } else {
            death->type = D_DEAD_BINDER_AND_CLEAR;
          }
        }
        break;
      }
      case BC_DEAD_BINDER_DONE: {
        binder_uintptr_t cookie;
        memcpy(&cookie, arg, sizeof(cookie));
        Death* found = nullptr;
        for (Death* d : proc->delivered_death) {
          if (d->cookie == cookie) {
            found = d;
            break;
          }
        }
        if (found == nullptr) break;
        proc->delivered_death.remove(found);
        found->delivered = false;
        if (found->type == D_DEAD_BINDER_AND_CLEAR) {
          found->type = D_CLEAR;
          enqueue_death(th, found);
        }
        break;
      }
      case kBcRequestFreezeNotification:
      case kBcClearFreezeNotification:
      case kBcFreezeNotificationDone:
        // Nothing is ever frozen here.
        break;
      default:
        nb_log("proc %d: unknown command %x", proc->pid, cmd);
        return EINVAL;
    }
    pos = next;
    (*consumed)++;
  }
  return 0;
}

// ------------------------------------------------------------ process death

void release_proc(Proc* proc) {
  VLOG("proc %d: released", proc->pid);
  for (auto it = g_ctx_mgr.begin(); it != g_ctx_mgr.end();) {
    it = (it->second->proc == proc) ? g_ctx_mgr.erase(it) : std::next(it);
  }
  std::vector<Thread*> threads;
  for (auto& [tid, th] : proc->threads) {
    (void)tid;
    threads.push_back(th);
  }
  for (Thread* th : threads) delete_thread(th);
  release_work(proc->todo);
  while (!proc->buffers.empty()) free_buffer(proc, proc->buffers.begin()->second);
  std::vector<Node*> nodes;
  for (auto& [ptr, n] : proc->nodes) {
    (void)ptr;
    nodes.push_back(n);
  }
  for (Node* node : nodes) {
    proc->nodes.erase(node->ptr);
    release_work(node->async_todo);
    if (node->queued) {
      node->queued->cancelled = true;
      node->queued = nullptr;
    }
    if (node->refs.empty()) {
      node->proc = nullptr;
      g_dead_nodes.insert(node);
      free_node(node);
      continue;
    }
    node->proc = nullptr;
    node->local_strong_refs = 0;
    node->local_weak_refs = 0;
    g_dead_nodes.insert(node);
    for (Ref* ref : node->refs) {
      Death* death = ref->death;
      if (death == nullptr || death->queued || death->delivered) continue;
      death->type = D_DEAD_BINDER;
      Work* w = new Work{W_DEATH};
      w->death = death;
      death->queued = w;
      enqueue(ref->proc->todo, w);
    }
  }
  while (!proc->refs_by_desc.empty()) free_ref(proc->refs_by_desc.begin()->second);
  for (Death* d : proc->delivered_death) {
    d->delivered = false;
    if (d->ref == nullptr) delete d;  // refs above freed the others
  }
  proc->delivered_death.clear();
  if (proc->map) munmap(proc->map, proc->map_size);
  g_procs.erase(proc->id);
  delete proc;
}

// ------------------------------------------------------------- dispatching

// After any change: answer the reads that can be answered now, and wake up
// processes that wait in poll().
void pump() {
  bool again = true;
  while (again) {
    again = false;
    for (auto& [id, proc] : g_procs) {
      (void)id;
      for (auto& [tid, th] : proc->threads) {
        (void)tid;
        if (th->waiting && try_complete_read(th)) again = true;
      }
    }
  }
  for (auto& [id, proc] : g_procs) {
    (void)id;
    if (!proc->has_poller || proc->wake_pending || proc->main == nullptr) continue;
    bool work = list_has_work(proc->todo);
    for (auto& [tid, th] : proc->threads) {
      (void)tid;
      if (th->polling && !th->waiting && list_has_work(th->todo)) work = true;
    }
    if (work) {
      char b = NB_WAKE;
      if (send(proc->main->fd, &b, 1, MSG_DONTWAIT | MSG_NOSIGNAL) == 1) proc->wake_pending = true;
    }
  }
}

void handle_message(Conn* c, uint32_t type, const uint8_t* p, size_t len) {
  switch (type) {
    case NB_HELLO_PROC: {
      nb_reply_hello rep = {};
      if (c->type != C_NEW || len < sizeof(nb_hello_proc)) {
        rep.error = EINVAL;
        reply(c, &rep, sizeof(rep));
        return;
      }
      nb_hello_proc hello;
      memcpy(&hello, p, sizeof(hello));
      hello.device[sizeof(hello.device) - 1] = '\0';
      Proc* proc = new Proc;
      proc->id = g_next_id++;
      proc->pid = c->pid ? c->pid : hello.pid;
      proc->uid = c->uid;
      proc->device = hello.device;
      proc->secctx = read_secctx(proc->pid);
      proc->main = c;
      g_procs[proc->id] = proc;
      c->type = C_MAIN;
      c->proc = proc;
      rep.proc_id = proc->id;
      VLOG("proc %d: opened %s (%s)", proc->pid, proc->device.c_str(), proc->secctx.c_str());
      reply(c, &rep, sizeof(rep));
      return;
    }
    case NB_HELLO_THREAD: {
      if (c->type != C_NEW || len < sizeof(nb_hello_thread)) {
        reply_status(c, EINVAL);
        return;
      }
      nb_hello_thread hello;
      memcpy(&hello, p, sizeof(hello));
      auto it = g_procs.find(hello.proc_id);
      if (it == g_procs.end() || (c->pid && it->second->pid != c->pid)) {
        reply_status(c, EBADF);
        return;
      }
      Proc* proc = it->second;
      auto old = proc->threads.find(hello.tid);
      if (old != proc->threads.end()) {
        // A thread that went away without BINDER_THREAD_EXIT; its id is reused.
        Thread* stale = old->second;
        if (stale->conn) stale->conn->thread = nullptr;
        delete_thread(stale);
      }
      Thread* th = new Thread;
      th->proc = proc;
      th->tid = hello.tid;
      th->conn = c;
      proc->threads[th->tid] = th;
      c->type = C_THREAD;
      c->proc = proc;
      c->thread = th;
      reply_status(c, 0);
      return;
    }
    default:
      break;
  }
  Thread* th = c->thread;
  if (c->type != C_THREAD || th == nullptr || th->dead) {
    reply_status(c, EBADF);
    return;
  }
  Proc* proc = th->proc;
  switch (type) {
    case NB_MMAP: {
      nb_mmap_req req;
      if (len < sizeof(req) || c->fds.empty()) {
        reply_status(c, EINVAL);
        return;
      }
      memcpy(&req, p, sizeof(req));
      int fd = c->fds.front();
      c->fds.pop_front();
      if (proc->map != nullptr || req.size == 0 || req.size > (4u << 20)) {
        close(fd);
        reply_status(c, EINVAL);
        return;
      }
      void* m = mmap(nullptr, req.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
      close(fd);
      if (m == MAP_FAILED) {
        reply_status(c, errno);
        return;
      }
      proc->map = static_cast<uint8_t*>(m);
      proc->map_size = req.size;
      proc->user_base = req.addr;
      proc->async_free = req.size / 2;
      reply_status(c, 0);
      return;
    }
    case NB_IOCTL: {
      nb_ioctl_req req = {};
      if (len >= sizeof(req)) memcpy(&req, p, sizeof(req));
      if (len < sizeof(req) || len - sizeof(req) < req.size) {
        reply_status(c, EINVAL);
        return;
      }
      const uint8_t* arg = p + sizeof(req);
      switch (req.cmd) {
        case BINDER_SET_MAX_THREADS: {
          if (req.size < 4) break;
          memcpy(&proc->max_threads, arg, 4);
          reply_status(c, 0);
          return;
        }
        case BINDER_SET_CONTEXT_MGR:
        case BINDER_SET_CONTEXT_MGR_EXT: {
          if (g_ctx_mgr.count(proc->device)) {
            reply_status(c, EBUSY);
            return;
          }
          struct flat_binder_object fbo = {};
          if (req.cmd == BINDER_SET_CONTEXT_MGR_EXT) {
            if (req.size < sizeof(fbo)) break;
            memcpy(&fbo, arg, sizeof(fbo));
          }
          Node* node = proc->nodes.count(fbo.binder) ? proc->nodes[fbo.binder]
                                                     : new_node(proc, fbo.binder, fbo.cookie, fbo.flags);
          node->local_weak_refs++;
          node->local_strong_refs++;
          node->has_strong_ref = true;
          node->has_weak_ref = true;
          g_ctx_mgr[proc->device] = node;
          nb_log("proc %d is the context manager of %s", proc->pid, proc->device.c_str());
          reply_status(c, 0);
          return;
        }
        case BINDER_THREAD_EXIT:
          release_thread(th);
          reply_status(c, 0);
          return;
        case BINDER_ENABLE_ONEWAY_SPAM_DETECTION:
          reply_status(c, 0);
          return;
        default:
          break;
      }
      reply_status(c, EINVAL);
      return;
    }
    case NB_WRITE_READ: {
      nb_write_read_req req = {};
      if (len >= sizeof(req)) memcpy(&req, p, sizeof(req));
      if (len < sizeof(req) || len - sizeof(req) < req.write_len || req.nfds > c->fds.size()) {
        nb_write_read_reply rep = {EINVAL, 0, 0, 0, 0};
        reply(c, &rep, sizeof(rep));
        return;
      }
      std::deque<int> fds;
      for (uint32_t i = 0; i < req.nfds; i++) {
        fds.push_back(c->fds.front());
        c->fds.pop_front();
      }
      proc->wake_pending = false;  // the process drained its wake-up bytes first
      uint32_t consumed = 0;
      int err = thread_write(th, p + sizeof(req), req.write_len, fds, &consumed, req.read_size > 0);
      for (int fd : fds) close(fd);  // fds no object took
      th->pending_write_cmds = consumed;
      th->pending_read_size = req.read_size;
      if (err != 0 || req.read_size == 0) {
        try_complete_read(th, err);
        th->need_return = false;
        return;
      }
      try_complete_read(th);
      return;
    }
    default:
      reply_status(c, EINVAL);
  }
}

void close_conn(Conn* c) {
  epoll_ctl(g_epoll, EPOLL_CTL_DEL, c->fd, nullptr);
  if (c->type == C_MAIN && c->proc) {
    Proc* proc = c->proc;
    proc->main = nullptr;
    for (auto& [id, conn] : g_conns) {
      (void)id;
      if (conn->proc == proc) {
        conn->proc = nullptr;
        conn->thread = nullptr;
      }
    }
    for (auto& [tid, th] : proc->threads) {
      (void)tid;
      th->conn = nullptr;
    }
    release_proc(proc);
  } else if (c->type == C_THREAD && c->thread) {
    c->thread->conn = nullptr;
    delete_thread(c->thread);
  }
  for (int fd : c->fds) close(fd);
  close(c->fd);
  g_conns.erase(c->fd);
  delete c;
}

void read_conn(Conn* c) {
  while (true) {
    static uint8_t buf[65536];  // single-threaded
    struct iovec iov = {buf, sizeof(buf)};
    struct msghdr msg = {};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(int) * NB_MAX_FDS)];
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    ssize_t n = recvmsg(c->fd, &msg, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
    if (n < 0 && errno == EINTR) continue;
    if (n < 0 && errno == EAGAIN) break;
    if (n <= 0) {
      c->broken = true;
      break;
    }
    for (struct cmsghdr* cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm)) {
      if (cm->cmsg_level != SOL_SOCKET || cm->cmsg_type != SCM_RIGHTS) continue;
      size_t k = (cm->cmsg_len - CMSG_LEN(0)) / sizeof(int);
      const int* got = reinterpret_cast<const int*>(CMSG_DATA(cm));
      for (size_t i = 0; i < k; i++) c->fds.push_back(got[i]);
    }
    c->in.insert(c->in.end(), buf, buf + n);
  }
  size_t pos = 0;
  while (c->in.size() - pos >= sizeof(nb_hdr)) {
    nb_hdr h;
    memcpy(&h, c->in.data() + pos, sizeof(h));
    if (h.len > (64u << 20)) {
      c->broken = true;
      break;
    }
    if (c->in.size() - pos - sizeof(h) < h.len) break;
    handle_message(c, h.type, c->in.data() + pos + sizeof(h), h.len);
    pos += sizeof(h) + h.len;
    if (g_conns.find(c->fd) == g_conns.end()) return;
  }
  c->in.erase(c->in.begin(), c->in.begin() + static_cast<long>(pos));
}

int listen_on(const char* path) {
  unlink(path);
  int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
  if (fd == -1) return -1;
  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
  if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0 ||
      listen(fd, 128) != 0) {
    nb_log("cannot listen on %s: %s", path, strerror(errno));
    close(fd);
    return -1;
  }
  chmod(path, 0666);
  return fd;
}

}  // namespace

int main(int argc, char** argv) {
  bool foreground = false;
  bool own_socket = false;
  const char* path = NB_SOCKET;
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "-f") == 0) {
      foreground = true;
    } else if (strcmp(argv[i], "-v") == 0) {
      g_verbose = true;
    } else if (strcmp(argv[i], "-s") == 0 && i + 1 < argc) {
      path = argv[++i];
      own_socket = true;
    } else {
      fprintf(stderr, "usage: nbinder [-f] [-v] [-s SOCKET]\n");
      return 2;
    }
  }
  if (!own_socket && getenv("ANDROID_NATIVE_ROOT") == nullptr) {
    nb_log("ANDROID_NATIVE_ROOT is not set: refusing to use the host's %s", NB_SOCKET);
    return 1;
  }
  signal(SIGPIPE, SIG_IGN);
  mkdir("/dev/socket", 0755);
  int lfd = listen_on(path);
  if (lfd == -1) return 1;
  g_epoll = epoll_create1(EPOLL_CLOEXEC);
  struct epoll_event ev = {};
  ev.events = EPOLLIN;
  ev.data.fd = lfd;
  epoll_ctl(g_epoll, EPOLL_CTL_ADD, lfd, &ev);
  nb_log("listening on %s", path);
  if (!foreground && daemon(1, 1) != 0) {
    nb_log("daemon: %s", strerror(errno));
    return 1;
  }
  while (true) {
    struct epoll_event events[64];
    int n = epoll_wait(g_epoll, events, 64, -1);
    if (n < 0) {
      if (errno == EINTR) continue;
      nb_log("epoll_wait: %s", strerror(errno));
      return 1;
    }
    for (int i = 0; i < n; i++) {
      int fd = events[i].data.fd;
      if (fd == lfd) {
        while (true) {
          int cfd = accept4(lfd, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
          if (cfd == -1) break;
          Conn* c = new Conn;
          c->fd = cfd;
          struct ucred cred = {};
          socklen_t clen = sizeof(cred);
          if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) == 0) {
            c->pid = cred.pid;
            c->uid = cred.uid;
          }
          g_conns[cfd] = c;
          struct epoll_event cev = {};
          cev.events = EPOLLIN | EPOLLRDHUP;
          cev.data.fd = cfd;
          epoll_ctl(g_epoll, EPOLL_CTL_ADD, cfd, &cev);
        }
        continue;
      }
      auto it = g_conns.find(fd);
      if (it == g_conns.end()) continue;
      Conn* c = it->second;
      read_conn(c);
      if (g_conns.count(fd) && c->broken) close_conn(c);
    }
    // Connections whose replies failed.
    std::vector<Conn*> broken;
    for (auto& [cfd, c] : g_conns) {
      (void)cfd;
      if (c->broken) broken.push_back(c);
    }
    for (Conn* c : broken) close_conn(c);
    pump();
  }
}
