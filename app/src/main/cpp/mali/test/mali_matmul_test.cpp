// Correctness test for the Mali backend's kernels against ggml's CPU backend (host only).
// Runs on any OpenCL implementation, e.g. PoCL (apt install pocl-opencl-icd ocl-icd-libopencl1):
//
//   L=third_party/llama.cpp; cmake -S $L -B build-host -DBUILD_SHARED_LIBS=ON -DLLAMA_BUILD_TOOLS=OFF && cmake --build build-host -j --target ggml
//   { printf 'R"CLC('; cat app/src/main/cpp/mali/ggml-mali-kernels.cl; printf ')CLC"\n'; } > /tmp/ggml-mali-kernels.inc
//   g++ -O2 -std=c++17 -I/tmp -Iapp/src/main/cpp/mali -Ithird_party/OpenCL-Headers -I$L/ggml/include -I$L/ggml/src \
//       app/src/main/cpp/mali/test/mali_matmul_test.cpp app/src/main/cpp/mali/ggml-mali.cpp \
//       -Lbuild-host/bin -lggml -lggml-base -lggml-cpu -ldl -Wl,-rpath,build-host/bin -o mali_test && ./mali_test
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml-mali.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

static bool run(ggml_type wt, int M, int K, int N, ggml_backend_t mali, ggml_backend_t cpu) {
    ggml_init_params ip = { ggml_tensor_overhead() * 8 + ggml_graph_overhead(), nullptr, true };
    ggml_context * ctx = ggml_init(ip);
    ggml_tensor * w = ggml_new_tensor_2d(ctx, wt, K, M);
    ggml_tensor * x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, K, N);
    ggml_tensor * y = ggml_mul_mat(ctx, w, x);
    ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, y);
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, ggml_backend_get_default_buffer_type(cpu));
    ggml_backend_buffer_set_usage(buf, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);

    std::mt19937 rng(42);
    std::normal_distribution<float> nd(0.f, 1.f);
    std::vector<float> wf((size_t) M * K), xf((size_t) N * K);
    for (auto & v : wf) v = nd(rng);
    for (auto & v : xf) v = nd(rng);
    std::vector<uint8_t> wq(ggml_nbytes(w));
    if (wt == GGML_TYPE_F16) ggml_fp32_to_fp16_row(wf.data(), (ggml_fp16_t *) wq.data(), (int64_t) M * K);
    else ggml_quantize_chunk(wt, wf.data(), wq.data(), 0, M, K, nullptr);
    ggml_backend_tensor_set(w, wq.data(), 0, wq.size());
    ggml_backend_tensor_set(x, xf.data(), 0, xf.size() * 4);

    if (!ggml_backend_supports_op(mali, y)) { printf("  NOT SUPPORTED by Mali backend\n"); return false; }
    std::vector<float> ref((size_t) M * N), out((size_t) M * N);
    ggml_backend_graph_compute(cpu, gf);
    ggml_backend_tensor_get(y, ref.data(), 0, ref.size() * 4);
    ggml_backend_tensor_set(y, std::vector<float>(ref.size(), -999.f).data(), 0, ref.size() * 4);
    if (ggml_backend_graph_compute(mali, gf) != GGML_STATUS_SUCCESS) { printf("  compute failed\n"); return false; }
    ggml_backend_tensor_get(y, out.data(), 0, out.size() * 4);

    double max_err = 0, max_ref = 0;
    for (size_t i = 0; i < ref.size(); i++) { max_err = std::max(max_err, (double) std::fabs(ref[i] - out[i])); max_ref = std::max(max_ref, (double) std::fabs(ref[i])); }
    const bool ok = max_err / max_ref < 2e-3;
    printf("  %-5s M=%d K=%d N=%d  max|ref|=%.2f  max err=%.5f  rel=%.2e  %s\n", ggml_type_name(wt), M, K, N, max_ref, max_err, max_err / max_ref, ok ? "OK" : "MISMATCH");
    ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    return ok;
}

int main() {
    ggml_backend_reg_t reg = ggml_backend_mali_reg(true);
    printf("status: %s\n", ggml_backend_mali_status());
    if (!reg) return 1;
    ggml_backend_t mali = ggml_backend_dev_init(ggml_backend_reg_dev_get(reg, 0), nullptr);
    ggml_backend_t cpu  = ggml_backend_cpu_init();
    bool ok = true;
    for (ggml_type t : {GGML_TYPE_Q8_0, GGML_TYPE_F16}) {
        ok &= run(t, 64, 256, 32, mali, cpu);
        ok &= run(t, 70, 512, 37, mali, cpu);
        ok &= run(t, 1, 64, 1, mali, cpu);
        ok &= run(t, 2048, 2048, 64, mali, cpu);
    }
    int64_t n; double ms; ggml_backend_mali_stats(&n, &ms);
    printf("%s (%lld GPU matmuls, %.1f ms)\n", ok ? "ALL OK" : "FAILURES", (long long) n, ms);
    return ok ? 0 : 1;
}
