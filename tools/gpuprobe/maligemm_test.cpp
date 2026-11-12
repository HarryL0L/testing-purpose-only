// maligemm-test: image-model-sized matrix multiplies (MUL_MAT) on the Vulkan GPU vs the CPU.
// Checks LlamaChat's Mali f16 GEMM fast path (ggml_vk_mali_gemm.cmake) for correctness and speed.
//   maligemm-test           correctness (all shapes, all types) + GPU speed
//   maligemm-test --quick   two shapes only
//   maligemm-test --profile where the fast path's time goes: full / without weight prep / without input
//                           prep / kernel only (timing only; the skipped variants give wrong results)
// Environment: LLAMACHAT_MALI_GEMM=0 (ggml's own matmul), f16 (pure f16 sums), 1 (force on any GPU).
// The fast path logs "Mali GEMM fast path on" once when it is used.

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

struct Shape { int M, K, N, B = 1; }; // B: batch (e.g. attention heads)

static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }

// runs out = W x X on `backend`; returns out (N x M floats), *sec = best time of `reps` runs
static std::vector<float> run(ggml_backend_t backend, ggml_type type, const Shape & s, const std::vector<uint8_t> & wdata,
                              const std::vector<float> & xdata, int reps, double * sec) {
    ggml_init_params ip = { ggml_tensor_overhead() * 8 + ggml_graph_overhead(), nullptr, true };
    ggml_context * ctx = ggml_init(ip);
    ggml_tensor * w = ggml_new_tensor_3d(ctx, type, s.K, s.M, s.B);
    ggml_tensor * x = ggml_new_tensor_3d(ctx, GGML_TYPE_F32, s.K, s.N, s.B);
    ggml_tensor * out = ggml_mul_mat(ctx, w, x);
    ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, out);
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, backend);
    ggml_backend_tensor_set(w, wdata.data(), 0, wdata.size());
    ggml_backend_tensor_set(x, xdata.data(), 0, xdata.size() * sizeof(float));
    double best = 1e9;
    for (int r = 0; r < reps; r++) {
        const double t0 = now();
        ggml_backend_graph_compute(backend, gf);
        ggml_backend_synchronize(backend);
        best = std::min(best, now() - t0);
    }
    *sec = best;
    std::vector<float> res((size_t) s.M * s.N * s.B);
    ggml_backend_tensor_get(out, res.data(), 0, res.size() * sizeof(float));
    ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    return res;
}

int main(int argc, char ** argv) {
    const bool quick = argc > 1 && strcmp(argv[1], "--quick") == 0;
    const bool profile = argc > 1 && strcmp(argv[1], "--profile") == 0;
    setenv("LLAMACHAT_MALI_GEMM_KQUANTS", "1", 0); // check the K-quant path too (default: ARM GPUs only)
    ggml_backend_t gpu = ggml_backend_init_by_name("Vulkan0", nullptr);
    if (!gpu) { fprintf(stderr, "no Vulkan0 backend\n"); return 1; }
    ggml_backend_t cpu = ggml_backend_cpu_init();
    ggml_backend_cpu_set_n_threads(cpu, 4);

    if (profile) {
        const Shape ps[] = { {2048, 2048, 1024}, {8192, 2048, 512}, {2048, 8192, 512}, {4096, 128, 4096, 2} };
        const ggml_type pt[] = { GGML_TYPE_F16, GGML_TYPE_Q5_K, GGML_TYPE_F32 };
        const char * skips[] = { "", "a", "b", "ab" };
        std::mt19937 prng(7);
        std::normal_distribution<float> pnd(0.0f, 1.0f);
        printf("%-6s %20s %10s %10s %10s %10s   (ms; full / no weight prep / no input prep / kernel only)\n",
               "type", "M x K x N", "full", "-a", "-b", "-ab");
        for (const Shape & s : ps) {
            std::vector<float> wf((size_t) s.M * s.K * s.B), xf((size_t) s.K * s.N * s.B);
            for (auto & v : wf) v = pnd(prng) * 0.05f;
            for (auto & v : xf) v = pnd(prng);
            for (ggml_type t : pt) {
                if (s.B > 1 && t == GGML_TYPE_Q5_K) continue;
                std::vector<uint8_t> wd(ggml_row_size(t, s.K) * s.M * s.B);
                if (t == GGML_TYPE_F32) memcpy(wd.data(), wf.data(), wd.size());
                else ggml_quantize_chunk(t, wf.data(), wd.data(), 0, s.M * s.B, s.K, nullptr);
                char dims[40];
                snprintf(dims, sizeof dims, s.B > 1 ? "%dx%dx%d x%d" : "%dx%dx%d", s.M, s.K, s.N, s.B);
                printf("%-6s %20s", ggml_type_name(t), dims);
                double full = 0, kern = 0;
                for (const char * sk : skips) {
                    setenv("LLAMACHAT_MALI_GEMM_SKIP", sk, 1);
                    double tg = 0;
                    run(gpu, t, s, wd, xf, 5, &tg);
                    if (!*sk) full = tg;
                    if (!strcmp(sk, "ab")) kern = tg;
                    printf(" %10.2f", tg * 1e3);
                    fflush(stdout);
                }
                const double fl = 2.0 * s.M * s.N * s.K * s.B;
                printf("   GFLOPS: full %.0f, kernel only %.0f\n", fl / full * 1e-9, fl / kern * 1e-9);
            }
        }
        unsetenv("LLAMACHAT_MALI_GEMM_SKIP");
        ggml_backend_free(gpu);
        ggml_backend_free(cpu);
        return 0;
    }

    // sizes from image models: Anima (2048 hidden, 8192 MLP), SD 1.5 (320/640/1280), small checks
    std::vector<Shape> shapes = quick ? std::vector<Shape>{ {256, 512, 128}, {2048, 2048, 1024} }
                                      : std::vector<Shape>{ {256, 512, 128}, {320, 320, 4096}, {2048, 2048, 1024},
                                                            {8192, 2048, 512}, {2048, 8192, 512}, {96, 1024, 64},
                                                            // attention, 4 heads: Q K^T (M = keys, K = head dim) and P V (M = head dim, K = keys)
                                                            {576, 128, 576, 4}, {128, 576, 576, 4},
                                                            // sizes that need padding: chat prompts (odd token counts), a vision MLP (K 4304)
                                                            {2048, 2048, 137}, {1152, 4304, 200}, {128, 520, 100, 4} };
    const ggml_type types[] = { GGML_TYPE_F16, GGML_TYPE_Q8_0, GGML_TYPE_Q4_0, GGML_TYPE_Q4_K, GGML_TYPE_Q5_K, GGML_TYPE_Q6_K, GGML_TYPE_F32 };
    const char * mode = getenv("LLAMACHAT_MALI_GEMM");
    printf("LLAMACHAT_MALI_GEMM=%s\n", mode ? mode : "(unset: on for ARM GPUs)");
    printf("%-6s %18s %12s %10s %10s\n", "type", "M x K x N", "rel. error", "GPU ms", "GPU GFLOPS");
    std::mt19937 rng(42);
    std::normal_distribution<float> nd(0.0f, 1.0f);
    int bad = 0;
    for (const Shape & s : shapes) {
        std::vector<float> wf((size_t) s.M * s.K * s.B), xf((size_t) s.K * s.N * s.B);
        for (auto & v : wf) v = nd(rng) * 0.05f; // weights ~ trained-model scale
        for (auto & v : xf) v = nd(rng);
        for (ggml_type t : types) {
            if (ggml_blck_size(t) > 1 && s.K % ggml_blck_size(t)) continue;
            if (s.B > 1 && t != GGML_TYPE_F16 && t != GGML_TYPE_F32) continue; // attention operands are f16 / f32
            std::vector<uint8_t> wd(ggml_row_size(t, s.K) * s.M * s.B);
            if (t == GGML_TYPE_F32) memcpy(wd.data(), wf.data(), wd.size());
            else ggml_quantize_chunk(t, wf.data(), wd.data(), 0, s.M * s.B, s.K, nullptr);
            double tg = 0, tc = 0;
            const std::vector<float> rg = run(gpu, t, s, wd, xf, quick ? 3 : 5, &tg);
            const std::vector<float> rc = run(cpu, t, s, wd, xf, 1, &tc);
            double e2 = 0, r2 = 0;
            bool finite = true;
            for (size_t i = 0; i < rg.size(); i++) {
                if (!std::isfinite(rg[i])) finite = false;
                const double d = (double) rg[i] - rc[i];
                e2 += d * d;
                r2 += (double) rc[i] * rc[i];
            }
            const double rel = std::sqrt(e2 / std::max(r2, 1e-30));
            if (getenv("MALIGEMM_DUMP") && rel > 2e-2) {
                // pattern of the error: per output column (m) within the first token row
                int wrong = 0;
                for (int m = 0; m < s.M; m++) if (std::fabs(rg[m] - rc[m]) > 0.05 * (std::fabs(rc[m]) + 0.1)) wrong++;
                printf("  token 0: %d of %d outputs wrong; first values GPU/CPU:", wrong, s.M);
                for (int m = 0; m < 8; m++) printf(" %.3f/%.3f", rg[m], rc[m]);
                printf("\n  wrong rows (m) by block of 16:");
                for (int m0 = 0; m0 < s.M; m0 += 16) {
                    int w = 0;
                    for (int m = m0; m < m0 + 16; m++) if (std::fabs(rg[m] - rc[m]) > 0.05 * (std::fabs(rc[m]) + 0.1)) w++;
                    printf(" %d", w);
                }
                printf("\n");
            }
            const bool ok = finite && rel < 2e-2;
            if (!ok) bad++;
            char dims[32];
            if (s.B > 1) snprintf(dims, sizeof dims, "%dx%dx%d x%d", s.M, s.K, s.N, s.B);
            else snprintf(dims, sizeof dims, "%dx%dx%d", s.M, s.K, s.N);
            printf("%-6s %18s %12.2e%s %9.2f %10.1f\n", ggml_type_name(t), dims, rel, ok ? " " : "!", tg * 1e3,
                   2.0 * s.M * s.N * s.K * s.B / tg * 1e-9);
            fflush(stdout);
        }
    }
    printf("%s (%d failed; rel. error = rms(GPU - CPU) / rms(CPU), limit 2e-2)\n", bad ? "FAIL" : "OK", bad);
    ggml_backend_free(gpu);
    ggml_backend_free(cpu);
    return bad ? 1 : 0;
}
