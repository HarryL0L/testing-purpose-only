# Attention in head groups (patched copy of stable-diffusion.cpp's src/core/ggml_extend.cpp).
#
# Without flash attention, stable-diffusion.cpp computes softmax(K^T Q) V for all heads at once, so
# the attention matrix is one tensor of L_q * L_k * heads floats: 1 GB at 1024 px (4096 tokens,
# 16 heads), 340 MB at 768 px. Phone GPUs address at most 256 MB per buffer binding (Mali-G77:
# maxStorageBufferRange 256 MB), and the memory peak is large. With this patch the heads are processed
# in groups whose attention matrix stays under LLAMACHAT_ATTN_GROUP_MB (default 192) and the results
# are concatenated; every multiply is then an ordinary batched MUL_MAT (the Mali GEMM fast path).
# Only used without a mask and without grouped-query attention; otherwise unchanged.

set(_sa_src ${SD_SRC}/src/core/ggml_extend.cpp)
set(_sa_dst ${CMAKE_CURRENT_BINARY_DIR}/sd_patch/ggml_extend.cpp)
file(READ ${_sa_src} _sa)

macro(_sa_patch old new)
    string(FIND "${_sa}" "${old}" _sa_pos)
    if(_sa_pos EQUAL -1)
        message(FATAL_ERROR "attention patch: snippet not found in ggml_extend.cpp:\n${old}")
    endif()
    string(REPLACE "${old}" "${new}" _sa "${_sa}")
endmacro()

_sa_patch([[
        auto kq = ggml_mul_mat(ctx, k, q);  // [N * n_head, L_q, L_k]
        ggml_prec_set_acc(kq, GGML_PREC_F32);
        kq = ggml_scale_inplace(ctx, kq, scale);
        if (mask) {
            kq = ggml_add_inplace(ctx, kq, mask);
        }
        kq = ggml_soft_max_inplace(ctx, kq);

        kqv = ggml_mul_mat(ctx, v, kq);  // [N * n_head, L_q, d_head]
]] [[
        // LlamaChat: heads in groups whose attention matrix stays under LLAMACHAT_ATTN_GROUP_MB
        const int64_t lc_bh       = q->ne[2];
        const size_t lc_per_head  = (size_t) L_q * (size_t) L_k * sizeof(float);
        const char * lc_env       = getenv("LLAMACHAT_ATTN_GROUP_MB");
        const size_t lc_limit     = (size_t) ((lc_env ? atof(lc_env) : 192.0) * 1048576.0);
        if (mask == nullptr && lc_limit > 0 && lc_per_head * (size_t) lc_bh > lc_limit &&
            k->ne[2] == lc_bh && v->ne[2] == lc_bh && ggml_is_contiguous(q) && ggml_is_contiguous(k) && ggml_is_contiguous(v)) {
            const int64_t lc_g = std::max<int64_t>(1, (int64_t) (lc_limit / lc_per_head));
            static int64_t lc_logged = -1;
            if (lc_logged != lc_g) {
                lc_logged = lc_g;
                LOG_INFO("attention in head groups of %d (%d heads, %.0f MB per head)", (int) lc_g, (int) lc_bh, lc_per_head / 1048576.0);
            }
            for (int64_t h0 = 0; h0 < lc_bh; h0 += lc_g) {
                const int64_t gh = std::min(lc_g, lc_bh - h0);
                auto qg  = ggml_view_3d(ctx, q, q->ne[0], q->ne[1], gh, q->nb[1], q->nb[2], h0 * q->nb[2]);
                auto kg  = ggml_view_3d(ctx, k, k->ne[0], k->ne[1], gh, k->nb[1], k->nb[2], h0 * k->nb[2]);
                auto vg  = ggml_view_3d(ctx, v, v->ne[0], v->ne[1], gh, v->nb[1], v->nb[2], h0 * v->nb[2]);
                auto kqg = ggml_mul_mat(ctx, kg, qg);  // [gh, L_q, L_k]
                ggml_prec_set_acc(kqg, GGML_PREC_F32);
                kqg      = ggml_scale_inplace(ctx, kqg, scale);
                kqg      = ggml_soft_max_inplace(ctx, kqg);
                auto og  = ggml_mul_mat(ctx, vg, kqg);  // [gh, L_q, d_head]
                kqv      = kqv == nullptr ? og : ggml_concat(ctx, kqv, og, 2);
            }
        } else {
        auto kq = ggml_mul_mat(ctx, k, q);  // [N * n_head, L_q, L_k]
        ggml_prec_set_acc(kq, GGML_PREC_F32);
        kq = ggml_scale_inplace(ctx, kq, scale);
        if (mask) {
            kq = ggml_add_inplace(ctx, kq, mask);
        }
        kq = ggml_soft_max_inplace(ctx, kq);

        kqv = ggml_mul_mat(ctx, v, kq);  // [N * n_head, L_q, d_head]
        }
]])
_sa_patch([[
#include <cmath>
]] [[
#include <algorithm>
#include <cmath>
#include <cstdlib>
]])

file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/sd_patch)
file(WRITE ${_sa_dst}.tmp "${_sa}")
file(COPY_FILE ${_sa_dst}.tmp ${_sa_dst} ONLY_IF_DIFFERENT)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${_sa_src})
get_target_property(_sa_sources stable-diffusion SOURCES)
list(FILTER _sa_sources EXCLUDE REGEX "(^|/)ggml_extend\\.cpp$")
list(APPEND _sa_sources ${_sa_dst})
set_property(TARGET stable-diffusion PROPERTY SOURCES ${_sa_sources})
target_include_directories(stable-diffusion PRIVATE ${SD_SRC}/src)
message(STATUS "LlamaChat: attention in head groups (patched ggml_extend.cpp)")
