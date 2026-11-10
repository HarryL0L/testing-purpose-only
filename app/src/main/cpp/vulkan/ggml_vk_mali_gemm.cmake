# Mali f16 GEMM fast path for ggml-vulkan (image engine copy: stable-diffusion.cpp's ggml).
# Included by ggml_vk11_patch.cmake through VK11_EXTRA_PATCHES, so it patches the same copy.
#
# Why: ggml's mul_mm reaches ~106 GFLOPS on a Mali-G77 (best tiles); a Mali-style kernel (outer
# product, 8 halfs per 128-bit load, packed f16 multiply-add, no shared memory) reaches ~390 with
# f32 folding and ~550 with pure f16 sums. See mali/gemm.comp.
#
# How: for MUL_MAT with f32 activations and many columns (images, prompts) whose sizes fit the
# kernel (M multiple of 32/64, N and K multiples of 64), ggml_vk_mul_mat_q_f16 first tries this path:
#   1. weights -> f16 (ggml's own to_fp16 dequant for quantized types) -> transposed to K-major,
#   2. activations f32 -> f16, transposed to K-major (reused when the next matmul has the same input),
#   3. the GEMM, split into dispatches of ~20 GFLOP so the GPU (which also draws the screen) is never
#      held for long.
# Unpacking the weights per multiply costs < 0.1 % of the multiply itself for thousands of columns.
#
# On by default on ARM GPUs with fp16 (f32-folded sums). LLAMACHAT_MALI_GEMM: 0 = off,
# f16 = pure f16 sums (faster, less precise), any other value = on for any GPU (for testing).

set(_mg_dir ${CMAKE_CURRENT_LIST_DIR}/mali)
set(_mg_gen ${CMAKE_CURRENT_BINARY_DIR}/lc_mali_gen)
set(_mg_headers "")
# name | source | defines
set(_mg_shaders
    "lc_mali_gemm_mix|gemm.comp|-DTM=4 -DTN=8 -DACC=2 -DWGX=8 -DWGY=8 -DUNROLL=8 -DKC=64"
    "lc_mali_gemm_f16|gemm.comp|-DTM=8 -DTN=8 -DACC=0 -DWGX=8 -DWGY=8 -DUNROLL=8 -DKC=64"
    "lc_mali_transpose_f16|transpose.comp|-DSRC_F32=0"
    "lc_mali_transpose_f32|transpose.comp|-DSRC_F32=1")
foreach(_s ${_mg_shaders})
    string(REPLACE "|" ";" _p "${_s}")
    list(GET _p 0 _name)
    list(GET _p 1 _src)
    list(GET _p 2 _defs)
    separate_arguments(_defs)
    add_custom_command(
        OUTPUT ${_mg_gen}/${_name}.h
        COMMAND ${CMAKE_COMMAND} -E make_directory ${_mg_gen}
        # the glslc wrapper: -O without fusing a*b+c into Fma (see glslc_vk11.sh.in)
        COMMAND ${Vulkan_GLSLC_EXECUTABLE} -fshader-stage=compute --target-env=vulkan1.1 -O ${_defs} ${_mg_dir}/${_src} -o ${_mg_gen}/${_name}.spv
        COMMAND ${CMAKE_COMMAND} -DIN=${_mg_gen}/${_name}.spv -DOUT=${_mg_gen}/${_name}.h -DNAME=${_name} -P ${CMAKE_CURRENT_LIST_DIR}/bin2h.cmake
        DEPENDS ${_mg_dir}/${_src} ${CMAKE_CURRENT_LIST_DIR}/bin2h.cmake ${CMAKE_CURRENT_LIST_DIR}/glslc_vk11.sh.in
        COMMENT "Mali GEMM shader ${_name}")
    list(APPEND _mg_headers ${_mg_gen}/${_name}.h)
endforeach()
set(_mg_includes "#pragma once\n#include <cstddef>\n#include <cstdint>\n")
foreach(_h ${_mg_headers})
    get_filename_component(_hn ${_h} NAME)
    string(APPEND _mg_includes "#include \"${_hn}\"\n")
endforeach()
file(MAKE_DIRECTORY ${_mg_gen})
file(WRITE ${_mg_gen}/lc_mali_shaders.h.tmp "${_mg_includes}")
file(COPY_FILE ${_mg_gen}/lc_mali_shaders.h.tmp ${_mg_gen}/lc_mali_shaders.h ONLY_IF_DIFFERENT)
add_custom_target(lc_mali_shaders DEPENDS ${_mg_headers})
add_dependencies(ggml-vulkan lc_mali_shaders)
target_include_directories(ggml-vulkan PRIVATE ${_mg_gen})

# 1. pipelines + mode, before ggml_vk_load_shaders
_vk_patch([[
void ggml_vk_load_shaders(vk_device& device, vk_pipeline requested) {
]] [[
// ---- LlamaChat: Mali f16 GEMM fast path (app/src/main/cpp/vulkan/ggml_vk_mali_gemm.cmake) ----
#include "lc_mali_shaders.h"
#include <map>
#include <mutex>
struct lc_mali_pipes { vk_pipeline gemm_mix, gemm_f16, tr_a, tr_b; };
static std::mutex lc_mali_mutex;
static std::map<const vk_device_struct *, lc_mali_pipes> lc_mali_map;
static lc_mali_pipes & lc_mali(const vk_device & device) {
    std::lock_guard<std::mutex> lock(lc_mali_mutex);
    return lc_mali_map[device.get()];
}
// 0: off, 1: f16 sums folded into f32 every 64 steps, 2: pure f16 sums
static int lc_mali_mode(const vk_device & device) {
    const char * e = getenv("LLAMACHAT_MALI_GEMM");
    if (e && strcmp(e, "0") == 0) return 0;
    if (!device->fp16) return 0;
    if (!(e && *e) && device->vendor_id != 0x13B5) return 0;
    return (e && strcmp(e, "f16") == 0) ? 2 : 1;
}
struct lc_mali_gemm_pc { uint32_t M, N, K, nbase; };
struct lc_mali_tr_pc { uint32_t rows, cols; };

void ggml_vk_load_shaders(vk_device& device, vk_pipeline requested) {
]])

# 2. create the pipelines with the others (compiled lazily, like ggml's)
_vk_patch([[
    ggml_vk_create_pipeline(device, device->pipeline_matmul_split_k_reduce, "split_k_reduce", split_k_reduce_len, split_k_reduce_data, "main", 2, 2 * sizeof(uint32_t), {256 * 4, 1, 1}, {}, 1);
]] [[
    ggml_vk_create_pipeline(device, device->pipeline_matmul_split_k_reduce, "split_k_reduce", split_k_reduce_len, split_k_reduce_data, "main", 2, 2 * sizeof(uint32_t), {256 * 4, 1, 1}, {}, 1);
    {
        lc_mali_pipes & lp = lc_mali(device);
        ggml_vk_create_pipeline(device, lp.gemm_mix, "lc_mali_gemm_mix", lc_mali_gemm_mix_size, lc_mali_gemm_mix, "main", 3, sizeof(lc_mali_gemm_pc), {32, 64, 1}, {}, 1);
        ggml_vk_create_pipeline(device, lp.gemm_f16, "lc_mali_gemm_f16", lc_mali_gemm_f16_size, lc_mali_gemm_f16, "main", 3, sizeof(lc_mali_gemm_pc), {64, 64, 1}, {}, 1);
        ggml_vk_create_pipeline(device, lp.tr_a, "lc_mali_transpose_f16", lc_mali_transpose_f16_size, lc_mali_transpose_f16, "main", 2, sizeof(lc_mali_tr_pc), {16, 16, 1}, {}, 1);
        ggml_vk_create_pipeline(device, lp.tr_b, "lc_mali_transpose_f32", lc_mali_transpose_f32_size, lc_mali_transpose_f32, "main", 2, sizeof(lc_mali_tr_pc), {16, 16, 1}, {}, 1);
    }
]])

# 3. the fast path, tried first by ggml_vk_mul_mat_q_f16
_vk_patch([[
static void ggml_vk_mul_mat_q_f16(ggml_backend_vk_context* ctx, vk_context& subctx, const ggml_tensor* src0, const ggml_tensor* src1, ggml_tensor* dst, bool disable_split_k, const std::function<void(vk_subbuffer)>& prepare_y = {}) {
]] [[
static bool lc_mali_gemm_try(ggml_backend_vk_context * ctx, vk_context & subctx, const ggml_tensor * src0, const ggml_tensor * src1, ggml_tensor * dst) {
    const int mode = lc_mali_mode(ctx->device);
    if (mode == 0) return false;
    if (src1->type != GGML_TYPE_F32 || dst->type != GGML_TYPE_F32) return false;
    // 2D, or a batch of independent 2D multiplies with the same batch shape (e.g. attention heads)
    if (src0->ne[2] != src1->ne[2] || src0->ne[3] != src1->ne[3]) return false;
    if (!ggml_is_contiguous(src0) || !ggml_is_contiguous(src1) || !ggml_is_contiguous(dst)) return false;
    if (get_misalign_bytes(ctx, src0) || get_misalign_bytes(ctx, src1) || get_misalign_bytes(ctx, dst)) return false;
    const uint32_t M = (uint32_t) src0->ne[1], K = (uint32_t) src0->ne[0], N = (uint32_t) src1->ne[1];
    const uint32_t batches = (uint32_t) (src0->ne[2] * src0->ne[3]);
    const uint32_t tile_m = mode == 2 ? 64 : 32;
    if (M % tile_m || N % 64 || K % 64 || N < 64) return false;
    // K-quants go through ggml's dequant shaders, which are correct on Mali (checked on a Mali-G77:
    // q4_K / q5_K / q6_K match the CPU) but came out wrong on lavapipe; so they are on for ARM GPUs
    // and opt-in elsewhere. LLAMACHAT_MALI_GEMM_KQUANTS=1 / 0 forces them on / off.
    switch (src0->type) {
        case GGML_TYPE_Q2_K: case GGML_TYPE_Q3_K: case GGML_TYPE_Q4_K: case GGML_TYPE_Q5_K: case GGML_TYPE_Q6_K: {
            const char * kq = getenv("LLAMACHAT_MALI_GEMM_KQUANTS");
            const bool on = kq ? strcmp(kq, "1") == 0 : ctx->device->vendor_id == 0x13B5;
            if (!on) return false;
            break;
        }
        default: break;
    }
    // f16 and f32 go straight into the transpose; other types are dequantized by ggml's to_fp16 first
    vk_pipeline to_fp16 = nullptr;
    if (src0->type != GGML_TYPE_F16 && src0->type != GGML_TYPE_F32) {
        to_fp16 = ggml_vk_get_to_fp16(ctx, src0->type);
        if (!to_fp16) return false;
    }
    // per-batch slices (bytes) and the alignment descriptor offsets need
    const uint64_t a_slice = src0->nb[2], b_slice = src1->nb[2], d_slice = dst->nb[2];
    const uint64_t align = ctx->device->properties.limits.minStorageBufferOffsetAlignment;
    if (batches > 1 && (a_slice % align || b_slice % align || d_slice % align)) return false;
    const uint64_t a_sz = (uint64_t) M * K * 2, b_sz = (uint64_t) N * K * 2, d_sz = (uint64_t) M * N * 4;
    const uint64_t maxr = ctx->device->properties.limits.maxStorageBufferRange;
    if (a_sz > maxr || b_sz > maxr || d_sz > maxr || a_slice > maxr || b_slice > maxr) return false;

    static bool logged = false;
    if (!logged) {
        logged = true;
        GGML_LOG_INFO("ggml_vulkan: Mali GEMM fast path on (%s sums)\n", mode == 2 ? "f16" : "f16 + f32 folding");
    }

    lc_mali_pipes & lp = lc_mali(ctx->device);
    vk_pipeline & gemm = mode == 2 ? lp.gemm_f16 : lp.gemm_mix;

    // ~20 GFLOP per dispatch
    const uint64_t flops_per_col = 2ull * M * K;
    uint32_t chunk = (uint32_t) std::max<uint64_t>(64, (20000000000ull / flops_per_col) / 64 * 64);
    chunk = std::min(chunk, N);
    const uint32_t nchunks = (N + chunk - 1) / chunk;

    if (ctx->prealloc_size_x < a_sz) { ctx->prealloc_size_x = a_sz; ggml_vk_preallocate_buffers(ctx, subctx); }
    if (ctx->prealloc_size_y < b_sz) { ctx->prealloc_size_y = b_sz; ggml_vk_preallocate_buffers(ctx, subctx); }
    if (to_fp16 && ctx->prealloc_size_split_k < a_sz) { ctx->prealloc_size_split_k = a_sz; ggml_vk_preallocate_buffers(ctx, subctx); }
    if (to_fp16) ggml_pipeline_request_descriptor_sets(ctx, to_fp16, batches);
    vk_pipeline & tr_a = src0->type == GGML_TYPE_F32 ? lp.tr_b : lp.tr_a; // f32 -> f16 transpose for f32 weights
    ggml_pipeline_request_descriptor_sets(ctx, tr_a, batches);
    ggml_pipeline_request_descriptor_sets(ctx, lp.tr_b, batches);
    // profiling only (results become wrong): LLAMACHAT_MALI_GEMM_SKIP with 'a' (weight prep), 'b' (input prep)
    const char * skip = getenv("LLAMACHAT_MALI_GEMM_SKIP");
    const bool skip_a = skip && strchr(skip, 'a'), skip_b = skip && strchr(skip, 'b');
    ggml_pipeline_request_descriptor_sets(ctx, gemm, nchunks * batches);

    vk_buffer d_Qx = nullptr, d_Qy = nullptr;
    size_t qx_off = 0, qy_off = 0;
    if (ctx->device->uma) {
        ggml_vk_host_get(ctx->device, src0->data, d_Qx, qx_off);
        ggml_vk_host_get(ctx->device, src1->data, d_Qy, qy_off);
    }
    if (!d_Qx) { d_Qx = ((ggml_backend_vk_buffer_context *) src0->buffer->context)->dev_buffer; qx_off = vk_tensor_offset(src0) + src0->view_offs; }
    if (!d_Qy) { d_Qy = ((ggml_backend_vk_buffer_context *) src1->buffer->context)->dev_buffer; qy_off = vk_tensor_offset(src1) + src1->view_offs; }
    vk_buffer d_D = ((ggml_backend_vk_buffer_context *) dst->buffer->context)->dev_buffer;
    const uint64_t d_off = vk_tensor_offset(dst) + dst->view_offs;
    GGML_ASSERT(d_Qx && d_Qy && d_D);
    const uint64_t a_bytes = batches > 1 ? a_slice : ggml_nbytes(src0);
    const uint64_t b_bytes = batches > 1 ? b_slice : ggml_nbytes(src1);

    for (uint32_t bt = 0; bt < batches; bt++) {
        // 1. weights -> K-major f16 in prealloc_x
        if (ctx->prealloc_x_need_sync || bt > 0) ggml_vk_sync_buffers(ctx, subctx);
        vk_subbuffer a16 = { d_Qx, qx_off + bt * a_slice, a_bytes };
        if (to_fp16 && !skip_a) {
            ggml_vk_sync_buffers(ctx, subctx); // prealloc_split_k may still be in use by an earlier op
            const std::vector<uint32_t> pc = { M, K, K, K, M * K };
            ggml_vk_dispatch_pipeline(ctx, subctx, to_fp16, { vk_subbuffer{ d_Qx, qx_off + bt * a_slice, a_bytes }, vk_subbuffer{ ctx->prealloc_split_k, 0, a_sz } },
                                      pc, { M * K, 1, 1 });
            ggml_vk_sync_buffers(ctx, subctx);
            a16 = { ctx->prealloc_split_k, 0, a_sz };
        }
        if (!skip_a) ggml_vk_dispatch_pipeline(ctx, subctx, tr_a, { a16, vk_subbuffer{ ctx->prealloc_x, 0, a_sz } }, lc_mali_tr_pc{ M, K }, { K, M, 1 });

        // 2. activations -> K-major f16 in prealloc_y (kept when the next 2D matmul has the same input)
        if (!skip_b && (batches > 1 || ctx->prealloc_y_last_pipeline_used != lp.tr_b.get() || ctx->prealloc_y_last_tensor_used != src1 || ctx->prealloc_y_last_k_padded)) {
            if (ctx->prealloc_y_need_sync || bt > 0) ggml_vk_sync_buffers(ctx, subctx);
            ggml_vk_dispatch_pipeline(ctx, subctx, lp.tr_b, { vk_subbuffer{ d_Qy, qy_off + bt * b_slice, b_bytes }, vk_subbuffer{ ctx->prealloc_y, 0, b_sz } },
                                      lc_mali_tr_pc{ N, K }, { K, N, 1 });
            ctx->prealloc_y_last_pipeline_used = batches > 1 ? nullptr : lp.tr_b.get();
            ctx->prealloc_y_last_tensor_used = src1;
            ctx->prealloc_y_last_k_padded = false;
        }
        ggml_vk_sync_buffers(ctx, subctx);

        // 3. the GEMM, in short dispatches
        for (uint32_t n0 = 0; n0 < N; n0 += chunk) {
            const uint32_t nc = std::min(chunk, N - n0);
            ggml_vk_dispatch_pipeline(ctx, subctx, gemm,
                                      { vk_subbuffer{ ctx->prealloc_x, 0, a_sz }, vk_subbuffer{ ctx->prealloc_y, 0, b_sz }, vk_subbuffer{ d_D, d_off + bt * d_slice, d_sz } },
                                      lc_mali_gemm_pc{ M, N, K, n0 }, { M, nc, 1 });
        }
    }
    ctx->prealloc_x_need_sync = true;
    ctx->prealloc_y_need_sync = true;
    return true;
}

static void ggml_vk_mul_mat_q_f16(ggml_backend_vk_context* ctx, vk_context& subctx, const ggml_tensor* src0, const ggml_tensor* src1, ggml_tensor* dst, bool disable_split_k, const std::function<void(vk_subbuffer)>& prepare_y = {}) {
    if (!prepare_y && lc_mali_gemm_try(ctx, subctx, src0, src1, dst)) return;
]])
