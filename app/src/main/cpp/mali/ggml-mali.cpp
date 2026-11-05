// LlamaChat experimental Mali GPU backend for ggml. See ggml-mali.h.

#define CL_TARGET_OPENCL_VERSION 120
#define CL_NO_PROTOTYPES
#include <CL/cl.h>
#include <CL/cl_function_types.h>

#include "ggml-mali.h"

#include "ggml-backend-impl.h"
#include "ggml.h"

#include <dlfcn.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

#ifdef __ANDROID__
#include <android/log.h>
#define MLOG(...) __android_log_print(ANDROID_LOG_INFO, "ggml-mali", __VA_ARGS__)
#else
#define MLOG(...) (fprintf(stderr, "ggml-mali: " __VA_ARGS__), fprintf(stderr, "\n"))
#endif

static const char * k_kernel_src =
#include "ggml-mali-kernels.inc"
;

// ------------------------------------------------------------------------------------------------
// OpenCL, loaded at runtime (Android has no libOpenCL in the NDK; it is a vendor library)
// ------------------------------------------------------------------------------------------------

#define CL_FUNCS(X)                                                                                  \
    X(clGetPlatformIDs) X(clGetDeviceIDs) X(clGetDeviceInfo) X(clCreateContext)                      \
    X(clCreateCommandQueue) X(clCreateBuffer) X(clReleaseMemObject) X(clCreateProgramWithSource)    \
    X(clBuildProgram) X(clGetProgramBuildInfo) X(clCreateKernel) X(clSetKernelArg)                  \
    X(clEnqueueNDRangeKernel) X(clFinish) X(clEnqueueMapBuffer) X(clEnqueueUnmapMemObject)          \
    X(clReleaseKernel) X(clReleaseProgram) X(clReleaseCommandQueue) X(clReleaseContext)

struct cl_api {
#define X(name) name##_fn name = nullptr;
    CL_FUNCS(X)
#undef X
};

static cl_api cl;

static bool load_opencl(std::string & why) {
    const char * candidates[] = {
#ifdef __ANDROID__
        "libOpenCL.so", "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so",
        "libOpenCL-pixel.so", "libOpenCL_system.so",
#else
        "libOpenCL.so.1", "libOpenCL.so",
#endif
    };
    void * h = nullptr;
    for (const char * c : candidates) {
        if ((h = dlopen(c, RTLD_NOW | RTLD_LOCAL)) != nullptr) break;
    }
    if (!h) {
        const char * e = dlerror();
        why = std::string("OpenCL library not found") + (e ? std::string(" (") + e + ")" : "");
        return false;
    }
#define X(name)                                                       \
    cl.name = (name##_fn) dlsym(h, #name);                            \
    if (!cl.name) { why = "OpenCL is missing " #name; return false; }
    CL_FUNCS(X)
#undef X
    return true;
}

// ------------------------------------------------------------------------------------------------
// Global state (one device)
// ------------------------------------------------------------------------------------------------

struct mali_state {
    cl_device_id     device  = nullptr;
    cl_context       context = nullptr;
    cl_command_queue queue   = nullptr;
    cl_program       program = nullptr;
    cl_kernel        k_quantize = nullptr, k_q8 = nullptr, k_f16 = nullptr;
    std::string      name, description, status;
    bool             arm_dot = false;
    std::mutex       mutex;
    // GPU views of weights, keyed by host pointer (weights don't move or change)
    std::unordered_map<const void *, std::pair<cl_mem, size_t>> weights;
    // scratch for quantized activations
    cl_mem scratch_q = nullptr, scratch_d = nullptr;
    size_t scratch_q_size = 0, scratch_d_size = 0;
};

static mali_state            g;
static std::atomic<int>      g_min_batch{32};
static std::atomic<int64_t>  g_n_ops{0};
static std::atomic<int64_t>  g_gpu_us{0};

static bool init_cl(bool any_device) {
    std::string why;
    if (!load_opencl(why)) { g.status = why; return false; }

    cl_platform_id platforms[8];
    cl_uint n_platforms = 0;
    if (cl.clGetPlatformIDs(8, platforms, &n_platforms) != CL_SUCCESS || n_platforms == 0) {
        g.status = "No OpenCL platform";
        return false;
    }
    for (cl_uint p = 0; p < n_platforms && !g.device; p++) {
        cl_device_id dev;
        cl_uint n = 0;
        if (cl.clGetDeviceIDs(platforms[p], CL_DEVICE_TYPE_GPU, 1, &dev, &n) == CL_SUCCESS && n > 0) {
            g.device = dev;
        } else if (any_device && cl.clGetDeviceIDs(platforms[p], CL_DEVICE_TYPE_ALL, 1, &dev, &n) == CL_SUCCESS && n > 0) {
            g.device = dev;
        }
    }
    if (!g.device) { g.status = "No OpenCL GPU found"; return false; }

    char buf[1024] = {0};
    cl.clGetDeviceInfo(g.device, CL_DEVICE_NAME, sizeof(buf) - 1, buf, nullptr);
    g.name = buf;
    std::string ext;
    {
        size_t sz = 0;
        cl.clGetDeviceInfo(g.device, CL_DEVICE_EXTENSIONS, 0, nullptr, &sz);
        ext.resize(sz);
        cl.clGetDeviceInfo(g.device, CL_DEVICE_EXTENSIONS, sz, ext.data(), nullptr);
    }
    // half is only used as a storage format (vload_half / vstore_half: core OpenCL 1.2), no cl_khr_fp16 needed
    g.arm_dot = ext.find("cl_arm_integer_dot_product_accumulate_int8") != std::string::npos;

    cl_int err = CL_SUCCESS;
    g.context = cl.clCreateContext(nullptr, 1, &g.device, nullptr, nullptr, &err);
    if (err != CL_SUCCESS) { g.status = "clCreateContext failed"; return false; }
    g.queue = cl.clCreateCommandQueue(g.context, g.device, 0, &err);
    if (err != CL_SUCCESS) { g.status = "clCreateCommandQueue failed"; return false; }

    g.program = cl.clCreateProgramWithSource(g.context, 1, &k_kernel_src, nullptr, &err);
    if (err == CL_SUCCESS) err = cl.clBuildProgram(g.program, 1, &g.device, "-cl-std=CL1.2 -cl-mad-enable", nullptr, nullptr);
    if (err != CL_SUCCESS) {
        size_t sz = 0;
        cl.clGetProgramBuildInfo(g.program, g.device, CL_PROGRAM_BUILD_LOG, 0, nullptr, &sz);
        std::string log(sz, '\0');
        cl.clGetProgramBuildInfo(g.program, g.device, CL_PROGRAM_BUILD_LOG, sz, log.data(), nullptr);
        MLOG("kernel build failed: %s", log.c_str());
        g.status = g.name + ": kernel build failed";
        return false;
    }
    g.k_quantize = cl.clCreateKernel(g.program, "quantize_q8_0", &err);
    g.k_q8       = cl.clCreateKernel(g.program, "mul_mat_q8_0", &err);
    g.k_f16      = cl.clCreateKernel(g.program, "mul_mat_f16", &err);
    if (!g.k_quantize || !g.k_q8 || !g.k_f16) { g.status = "clCreateKernel failed"; return false; }

    g.description = g.name + (g.arm_dot ? " (OpenCL, int8 dot)" : " (OpenCL)");
    g.status = g.description;
    MLOG("%s", g.status.c_str());
    return true;
}

// ------------------------------------------------------------------------------------------------
// Matrix multiplication
// ------------------------------------------------------------------------------------------------

static bool is_cpu_layout_buffer(ggml_backend_buffer_t buf);

static bool mul_mat_supported(const ggml_tensor * op) {
    if (op->op != GGML_OP_MUL_MAT) return false;
    const ggml_tensor * w = op->src[0];
    const ggml_tensor * x = op->src[1];
    if (!w || !x) return false;
    if (w->type != GGML_TYPE_Q8_0 && w->type != GGML_TYPE_F16) return false;
    if (x->type != GGML_TYPE_F32 || op->type != GGML_TYPE_F32) return false;
    if (w->ne[2] != 1 || w->ne[3] != 1) return false;                      // 2D weights only
    if (!ggml_is_contiguous(w) || !ggml_is_contiguous(x) || !ggml_is_contiguous(op)) return false;
    if (w->type == GGML_TYPE_Q8_0 && w->ne[0] % 32 != 0) return false;
    // only weights, and only in plain ggml layout (not repacked CPU buffers)
    if (!w->buffer || ggml_backend_buffer_get_usage(w->buffer) != GGML_BACKEND_BUFFER_USAGE_WEIGHTS) return false;
    if (!is_cpu_layout_buffer(w->buffer)) return false;
    if (x->buffer && !ggml_backend_buffer_is_host(x->buffer)) return false;
    return true;
}

static cl_mem weight_mem(const ggml_tensor * w) {
    const size_t size = ggml_nbytes(w);
    auto it = g.weights.find(w->data);
    if (it != g.weights.end() && it->second.second == size) return it->second.first;
    if (it != g.weights.end()) {
        cl.clReleaseMemObject(it->second.first);
        g.weights.erase(it);
    }
    cl_int err;
    cl_mem m = cl.clCreateBuffer(g.context, CL_MEM_READ_ONLY | CL_MEM_USE_HOST_PTR, size, w->data, &err);
    if (err != CL_SUCCESS) return nullptr;
    g.weights[w->data] = {m, size};
    return m;
}

static cl_mem scratch(cl_mem & mem, size_t & cur, size_t need) {
    if (need <= cur && mem) return mem;
    if (mem) cl.clReleaseMemObject(mem);
    cl_int err;
    mem = cl.clCreateBuffer(g.context, CL_MEM_READ_WRITE, need, nullptr, &err);
    cur = err == CL_SUCCESS ? need : 0;
    if (err != CL_SUCCESS) mem = nullptr;
    return mem;
}

static bool run_mul_mat(ggml_tensor * dst) {
    const ggml_tensor * w = dst->src[0];
    const ggml_tensor * x = dst->src[1];
    const int M = (int) w->ne[1];
    const int K = (int) w->ne[0];
    const int N = (int) (x->ne[1] * x->ne[2] * x->ne[3]);

    const auto t0 = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(g.mutex);

    cl_mem wm = weight_mem(w);
    if (!wm) { MLOG("%s: can't wrap weights (%zu bytes)", dst->name, ggml_nbytes(w)); return false; }
    cl_int err;
    cl_mem xm = cl.clCreateBuffer(g.context, CL_MEM_READ_ONLY | CL_MEM_USE_HOST_PTR, ggml_nbytes(x), x->data, &err);
    if (err != CL_SUCCESS) { MLOG("%s: can't wrap input (%d)", dst->name, err); return false; }
    cl_mem dm = cl.clCreateBuffer(g.context, CL_MEM_WRITE_ONLY | CL_MEM_USE_HOST_PTR, ggml_nbytes(dst), dst->data, &err);
    if (err != CL_SUCCESS) { MLOG("%s: can't wrap output (%d)", dst->name, err); cl.clReleaseMemObject(xm); return false; }

    const size_t global[2] = { (size_t) (M + 3) / 4, (size_t) (N + 3) / 4 };
    bool ok = true;
    if (w->type == GGML_TYPE_Q8_0) {
        const int nb = K / 32;
        const int n_blocks = nb * N;
        cl_mem qm = scratch(g.scratch_q, g.scratch_q_size, (size_t) n_blocks * 32);
        cl_mem dq = scratch(g.scratch_d, g.scratch_d_size, (size_t) n_blocks * 2);
        ok = qm && dq;
        if (ok) {
            cl.clSetKernelArg(g.k_quantize, 0, sizeof(cl_mem), &xm);
            cl.clSetKernelArg(g.k_quantize, 1, sizeof(cl_mem), &qm);
            cl.clSetKernelArg(g.k_quantize, 2, sizeof(cl_mem), &dq);
            cl.clSetKernelArg(g.k_quantize, 3, sizeof(int), &n_blocks);
            const size_t gq = (size_t) n_blocks;
            ok = cl.clEnqueueNDRangeKernel(g.queue, g.k_quantize, 1, nullptr, &gq, nullptr, 0, nullptr, nullptr) == CL_SUCCESS;
        }
        if (ok) {
            const int stride = (int) w->nb[1];
            cl.clSetKernelArg(g.k_q8, 0, sizeof(cl_mem), &wm);
            cl.clSetKernelArg(g.k_q8, 1, sizeof(cl_mem), &qm);
            cl.clSetKernelArg(g.k_q8, 2, sizeof(cl_mem), &dq);
            cl.clSetKernelArg(g.k_q8, 3, sizeof(cl_mem), &dm);
            cl.clSetKernelArg(g.k_q8, 4, sizeof(int), &M);
            cl.clSetKernelArg(g.k_q8, 5, sizeof(int), &N);
            cl.clSetKernelArg(g.k_q8, 6, sizeof(int), &nb);
            cl.clSetKernelArg(g.k_q8, 7, sizeof(int), &stride);
            ok = cl.clEnqueueNDRangeKernel(g.queue, g.k_q8, 2, nullptr, global, nullptr, 0, nullptr, nullptr) == CL_SUCCESS;
        }
    } else {
        const int ws = (int) (w->nb[1] / sizeof(ggml_fp16_t));
        const int xs = (int) (x->nb[1] / sizeof(float));
        cl.clSetKernelArg(g.k_f16, 0, sizeof(cl_mem), &wm);
        cl.clSetKernelArg(g.k_f16, 1, sizeof(cl_mem), &xm);
        cl.clSetKernelArg(g.k_f16, 2, sizeof(cl_mem), &dm);
        cl.clSetKernelArg(g.k_f16, 3, sizeof(int), &M);
        cl.clSetKernelArg(g.k_f16, 4, sizeof(int), &N);
        cl.clSetKernelArg(g.k_f16, 5, sizeof(int), &K);
        cl.clSetKernelArg(g.k_f16, 6, sizeof(int), &ws);
        cl.clSetKernelArg(g.k_f16, 7, sizeof(int), &xs);
        ok = cl.clEnqueueNDRangeKernel(g.queue, g.k_f16, 2, nullptr, global, nullptr, 0, nullptr, nullptr) == CL_SUCCESS;
    }
    if (ok) {
        // with CL_MEM_USE_HOST_PTR the result is guaranteed to be in dst->data after a map
        void * p = cl.clEnqueueMapBuffer(g.queue, dm, CL_TRUE, CL_MAP_READ, 0, ggml_nbytes(dst), 0, nullptr, nullptr, &err);
        ok = err == CL_SUCCESS && p;
        if (ok) cl.clEnqueueUnmapMemObject(g.queue, dm, p, 0, nullptr, nullptr);
        cl.clFinish(g.queue);
    }
    cl.clReleaseMemObject(xm);
    cl.clReleaseMemObject(dm);
    if (!ok) MLOG("%s: kernel failed (M=%d N=%d K=%d, %s)", dst->name, M, N, K, ggml_type_name(w->type));

    g_n_ops++;
    g_gpu_us += std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
    return ok;
}

// ------------------------------------------------------------------------------------------------
// Buffer type: host memory (Mali shares RAM with the CPU)
// ------------------------------------------------------------------------------------------------

struct mali_buffer { void * data; size_t size; };

static void   buf_free(ggml_backend_buffer_t b) { auto * c = (mali_buffer *) b->context; free(c->data); delete c; }
static void * buf_base(ggml_backend_buffer_t b) { return ((mali_buffer *) b->context)->data; }
static void   buf_memset(ggml_backend_buffer_t, ggml_tensor * t, uint8_t v, size_t off, size_t n) { memset((char *) t->data + off, v, n); }
static void   buf_set(ggml_backend_buffer_t, ggml_tensor * t, const void * d, size_t off, size_t n) { memcpy((char *) t->data + off, d, n); }
static void   buf_get(ggml_backend_buffer_t, const ggml_tensor * t, void * d, size_t off, size_t n) { memcpy(d, (const char *) t->data + off, n); }
static bool   buf_cpy(ggml_backend_buffer_t, const ggml_tensor * src, ggml_tensor * dst) {
    if (src->buffer && ggml_backend_buffer_is_host(src->buffer)) {
        memcpy(dst->data, src->data, ggml_nbytes(src));
        return true;
    }
    return false;
}
static void   buf_clear(ggml_backend_buffer_t b, uint8_t v) { auto * c = (mali_buffer *) b->context; memset(c->data, v, c->size); }

static const ggml_backend_buffer_i k_buffer_iface = {
    /* .free_buffer   = */ buf_free,
    /* .get_base      = */ buf_base,
    /* .init_tensor   = */ nullptr,
    /* .memset_tensor = */ buf_memset,
    /* .set_tensor    = */ buf_set,
    /* .get_tensor    = */ buf_get,
    /* .set_tensor_2d = */ nullptr,
    /* .get_tensor_2d = */ nullptr,
    /* .cpy_tensor    = */ buf_cpy,
    /* .clear         = */ buf_clear,
    /* .reset         = */ nullptr,
    /* .set_tensor_n  = */ nullptr,
    /* .get_tensor_n  = */ nullptr,
    /* .cpy_tensor_n  = */ nullptr,
};

static const char * buft_name(ggml_backend_buffer_type_t) { return "Mali"; }
static ggml_backend_buffer_t buft_alloc(ggml_backend_buffer_type_t buft, size_t size) {
    const size_t aligned = (size + 4095) & ~(size_t) 4095;
    void * p = nullptr;
    if (posix_memalign(&p, 4096, aligned ? aligned : 4096) != 0) return nullptr;
    return ggml_backend_buffer_init(buft, k_buffer_iface, new mali_buffer{p, size}, size);
}
static size_t buft_alignment(ggml_backend_buffer_type_t) { return 64; }
static bool   buft_is_host(ggml_backend_buffer_type_t) { return true; }

static ggml_backend_device g_device;
static ggml_backend_buffer_type g_buft = {
    /* .iface = */ {
        /* .get_name         = */ buft_name,
        /* .alloc_buffer     = */ buft_alloc,
        /* .alloc_buffer_n   = */ nullptr,
        /* .get_alignment    = */ buft_alignment,
        /* .get_max_size     = */ nullptr,
        /* .get_alloc_size   = */ nullptr,
        /* .get_alloc_size_n = */ nullptr,
        /* .is_host          = */ buft_is_host,
    },
    /* .device  = */ &g_device,
    /* .context = */ nullptr,
};

// weights we can read as-is: our buffers and plain CPU buffers (not the CPU repack/KleidiAI ones)
static bool is_cpu_layout_buffer(ggml_backend_buffer_t buf) {
    ggml_backend_buffer_type_t bt = ggml_backend_buffer_get_type(buf);
    if (bt == &g_buft) return true;
    if (!ggml_backend_buft_is_host(bt)) return false;
    const char * n = ggml_backend_buft_name(bt);
    return strcmp(n, "CPU") == 0 || strcmp(n, "CPU_Mapped") == 0;
}

// ------------------------------------------------------------------------------------------------
// Backend
// ------------------------------------------------------------------------------------------------

static const char * be_name(ggml_backend_t) { return "Mali"; }
static void         be_free(ggml_backend_t b) { delete b; }

static ggml_status be_graph_compute(ggml_backend_t, ggml_cgraph * cgraph) {
    for (int i = 0; i < ggml_graph_n_nodes(cgraph); i++) {
        ggml_tensor * node = ggml_graph_node(cgraph, i);
        switch (node->op) {
            case GGML_OP_NONE: case GGML_OP_RESHAPE: case GGML_OP_VIEW: case GGML_OP_PERMUTE: case GGML_OP_TRANSPOSE:
                continue;
            case GGML_OP_MUL_MAT:
                if (!node->data || !node->src[1]->data) { MLOG("%s: tensor without data", node->name); return GGML_STATUS_FAILED; }
                if (!run_mul_mat(node)) return GGML_STATUS_FAILED;
                continue;
            default:
                MLOG("unsupported op %s", ggml_op_name(node->op));
                return GGML_STATUS_FAILED;
        }
    }
    return GGML_STATUS_SUCCESS;
}

static ggml_guid_t mali_guid() {
    static ggml_guid guid = { 0x6c, 0x6c, 0x61, 0x6d, 0x61, 0x63, 0x68, 0x61, 0x74, 0x2d, 0x6d, 0x61, 0x6c, 0x69, 0x00, 0x01 };
    return &guid;
}

static const ggml_backend_i k_backend_iface = {
    /* .get_name            = */ be_name,
    /* .free                = */ be_free,
    /* .set_tensor_async    = */ nullptr,
    /* .get_tensor_async    = */ nullptr,
    /* .set_tensor_2d_async = */ nullptr,
    /* .get_tensor_2d_async = */ nullptr,
    /* .cpy_tensor_async    = */ nullptr,
    /* .synchronize         = */ nullptr,
    /* .graph_plan_create   = */ nullptr,
    /* .graph_plan_free     = */ nullptr,
    /* .graph_plan_update   = */ nullptr,
    /* .graph_plan_compute  = */ nullptr,
    /* .graph_compute       = */ be_graph_compute,
    /* .event_record        = */ nullptr,
    /* .event_wait          = */ nullptr,
    /* .graph_optimize      = */ nullptr,
};

// ------------------------------------------------------------------------------------------------
// Device + registration
// ------------------------------------------------------------------------------------------------

static void dev_memory(ggml_backend_dev_t, size_t * free_mem, size_t * total) {
    const long pages = sysconf(_SC_PHYS_PAGES), avail = sysconf(_SC_AVPHYS_PAGES), page = sysconf(_SC_PAGE_SIZE);
    *total    = (size_t) pages * page;
    *free_mem = (size_t) avail * page;
}
static const char * dev_name(ggml_backend_dev_t) { return "Mali"; }
static const char * dev_desc(ggml_backend_dev_t) { return g.description.c_str(); }
static enum ggml_backend_dev_type dev_type(ggml_backend_dev_t) { return GGML_BACKEND_DEVICE_TYPE_GPU; }
static void dev_props(ggml_backend_dev_t d, ggml_backend_dev_props * p) {
    p->name        = dev_name(d);
    p->description = dev_desc(d);
    dev_memory(d, &p->memory_free, &p->memory_total);
    p->type      = GGML_BACKEND_DEVICE_TYPE_GPU;
    p->device_id = nullptr;
    p->caps      = { /* async */ false, /* host_buffer */ false, /* from_host_ptr */ false, /* events */ false, /* mmap */ false };
}
static ggml_backend_t dev_init(ggml_backend_dev_t d, const char *) {
    return new ggml_backend{ mali_guid(), k_backend_iface, d, nullptr };
}
static ggml_backend_buffer_type_t dev_buft(ggml_backend_dev_t) { return &g_buft; }
static bool dev_supports_op(ggml_backend_dev_t, const ggml_tensor * op) {
    if (op->op != GGML_OP_MUL_MAT || !mul_mat_supported(op)) return false;
    // Weights in our own buffers (e.g. the vision encoder with use_gpu): always.
    // Weights in CPU buffers: only big batches. The scheduler picks the first backend that accepts
    // the weights' buffer and the op, so without this the GPU would also get token-by-token
    // generation, where it can't beat the CPU.
    if (ggml_backend_buffer_get_type(op->src[0]->buffer) == &g_buft) return true;
    const ggml_tensor * x = op->src[1];
    return x->ne[1] * x->ne[2] * x->ne[3] >= g_min_batch.load();
}
static bool dev_supports_buft(ggml_backend_dev_t, ggml_backend_buffer_type_t bt) {
    return bt == &g_buft || ggml_backend_buft_is_host(bt);
}
static bool dev_offload_op(ggml_backend_dev_t, const ggml_tensor * op) {
    if (!mul_mat_supported(op)) return false;
    const ggml_tensor * x = op->src[1];
    return x->ne[1] * x->ne[2] * x->ne[3] >= g_min_batch.load();
}

static const ggml_backend_device_i k_device_iface = {
    /* .get_name             = */ dev_name,
    /* .get_description      = */ dev_desc,
    /* .get_memory           = */ dev_memory,
    /* .get_type             = */ dev_type,
    /* .get_props            = */ dev_props,
    /* .init_backend         = */ dev_init,
    /* .get_buffer_type      = */ dev_buft,
    /* .get_host_buffer_type = */ nullptr,
    /* .buffer_from_host_ptr = */ nullptr,
    /* .supports_op          = */ dev_supports_op,
    /* .supports_buft        = */ dev_supports_buft,
    /* .offload_op           = */ dev_offload_op,
    /* .event_new            = */ nullptr,
    /* .event_free           = */ nullptr,
    /* .event_synchronize    = */ nullptr,
};

static const char * reg_name(ggml_backend_reg_t) { return "Mali"; }
static size_t reg_count(ggml_backend_reg_t) { return 1; }
static ggml_backend_dev_t reg_dev(ggml_backend_reg_t, size_t) { return &g_device; }

static const ggml_backend_reg_i k_reg_iface = {
    /* .get_name         = */ reg_name,
    /* .get_device_count = */ reg_count,
    /* .get_device       = */ reg_dev,
    /* .get_proc_address = */ nullptr,
};

static ggml_backend_reg g_reg = { GGML_BACKEND_API_VERSION, k_reg_iface, nullptr };

extern "C" ggml_backend_reg_t ggml_backend_mali_reg(bool any_device) {
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [&]() {
        ok = init_cl(any_device);
        g_device = { k_device_iface, &g_reg, nullptr };
    });
    return ok ? &g_reg : nullptr;
}

extern "C" const char * ggml_backend_mali_status(void) { return g.status.c_str(); }
extern "C" void ggml_backend_mali_set_min_batch(int n) { g_min_batch = n > 0 ? n : 1; }
extern "C" void ggml_backend_mali_stats(int64_t * n_ops, double * gpu_ms) {
    *n_ops  = g_n_ops.load();
    *gpu_ms = g_gpu_us.load() / 1000.0;
}
extern "C" void ggml_backend_mali_reset_stats(void) { g_n_ops = 0; g_gpu_us = 0; }
