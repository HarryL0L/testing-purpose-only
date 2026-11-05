// OpenCL kernels for the LlamaChat Mali backend (matrix multiplication only).
//
// Written for ggml's own layouts so no repacking is needed. The approach follows Google's
// ML Drift (Apache-2.0) on Mali: quantize activations to int8 and use the Arm int8 dot-product
// instruction (cl_arm_integer_dot_product_accumulate_int8), with register tiles per work-item
// and no dependence on a particular sub-group size (Mali can't guarantee one).
//
// Layouts (ggml):
//   Q8_0 weight block: { half d; char qs[32]; } = 34 bytes, rows of nb blocks
//   activations X: [N][K] float, row-major (ggml src1, contiguous)
//   output D:      [N][M] float (ggml dst, contiguous): D[n*M + m] = sum_k W[m][k] * X[n][k]

// half is used only as a storage format (vload_half / vstore_half), which is core OpenCL 1.2

#ifdef cl_arm_integer_dot_product_accumulate_int8
#pragma OPENCL EXTENSION cl_arm_integer_dot_product_accumulate_int8 : enable
#define DOT_ACC(a, b, c) arm_dot_acc((a), (b), (c))
#else
inline int dot_acc_fallback(char4 a, char4 b, int c) {
    return c + (int) a.x * b.x + (int) a.y * b.y + (int) a.z * b.z + (int) a.w * b.w;
}
#define DOT_ACC(a, b, c) dot_acc_fallback((a), (b), (c))
#endif

#define QK8_0 32
#define Q8_0_BLOCK 34

// One work-item per 32-value block: same rounding as ggml's quantize_row_q8_0
// (scale = amax / 127 stored as fp16, q = round(x / scale)).
__kernel void quantize_q8_0(__global const float * x, __global char * qx, __global half * dx, int n_blocks) {
    const int b = get_global_id(0);
    if (b >= n_blocks) return;
    __global const float * p = x + (size_t) b * QK8_0;
    float amax = 0.0f;
    for (int i = 0; i < QK8_0; i++) amax = fmax(amax, fabs(p[i]));
    const float d  = amax / 127.0f;
    const float id = d != 0.0f ? 1.0f / d : 0.0f;
    __global char * q = qx + (size_t) b * QK8_0;
    for (int i = 0; i < QK8_0; i++) q[i] = (char) round(p[i] * id);
    vstore_half(d, b, dx);
}

#define TM 4  // weight rows per work-item
#define TN 4  // tokens per work-item

__kernel void mul_mat_q8_0(__global const uchar * W, __global const char * QX, __global const half * DX,
                           __global float * D, int M, int N, int nb, int w_row_stride) {
    const int m0 = get_global_id(0) * TM;
    const int n0 = get_global_id(1) * TN;
    if (m0 >= M || n0 >= N) return;

    float acc[TM][TN];
    for (int i = 0; i < TM; i++) for (int t = 0; t < TN; t++) acc[i][t] = 0.0f;

    for (int b = 0; b < nb; b++) {
        float dw[TM];
        char4 wq[TM][8];
        for (int i = 0; i < TM; i++) {
            const int m = min(m0 + i, M - 1);
            __global const uchar * blk = W + (size_t) m * w_row_stride + (size_t) b * Q8_0_BLOCK;
            dw[i] = vload_half(0, (__global const half *) blk);
            for (int j = 0; j < 8; j++) wq[i][j] = vload4(0, (__global const char *) (blk + 2 + 4 * j));
        }
        for (int t = 0; t < TN; t++) {
            const int n = min(n0 + t, N - 1);
            const size_t xb = (size_t) n * nb + b;
            __global const char * xq = QX + xb * QK8_0;
            const float dx = vload_half(xb, DX);
            char4 xv[8];
            for (int j = 0; j < 8; j++) xv[j] = vload4(j, xq);
            for (int i = 0; i < TM; i++) {
                int s = 0;
                for (int j = 0; j < 8; j++) s = DOT_ACC(wq[i][j], xv[j], s);
                acc[i][t] += (float) s * dw[i] * dx;
            }
        }
    }
    for (int t = 0; t < TN; t++) {
        const int n = n0 + t;
        if (n >= N) break;
        for (int i = 0; i < TM; i++) {
            const int m = m0 + i;
            if (m < M) D[(size_t) n * M + m] = acc[i][t];
        }
    }
}

// F16 weights x F32 activations (e.g. vision projectors stored as f16)
__kernel void mul_mat_f16(__global const half * W, __global const float * X, __global float * D,
                          int M, int N, int K, int w_row_stride, int x_row_stride) {
    const int m0 = get_global_id(0) * TM;
    const int n0 = get_global_id(1) * TN;
    if (m0 >= M || n0 >= N) return;

    float acc[TM][TN];
    for (int i = 0; i < TM; i++) for (int t = 0; t < TN; t++) acc[i][t] = 0.0f;

    int k = 0;
    for (; k + 4 <= K; k += 4) {
        float4 w[TM];
        for (int i = 0; i < TM; i++) w[i] = vload_half4(0, W + (size_t) min(m0 + i, M - 1) * w_row_stride + k);
        for (int t = 0; t < TN; t++) {
            const float4 x = vload4(0, X + (size_t) min(n0 + t, N - 1) * x_row_stride + k);
            for (int i = 0; i < TM; i++) acc[i][t] += dot(w[i], x);
        }
    }
    for (; k < K; k++) {
        for (int i = 0; i < TM; i++) {
            const float w = vload_half((size_t) min(m0 + i, M - 1) * w_row_stride + k, W);
            for (int t = 0; t < TN; t++) acc[i][t] += w * X[(size_t) min(n0 + t, N - 1) * x_row_stride + k];
        }
    }
    for (int t = 0; t < TN; t++) {
        const int n = n0 + t;
        if (n >= N) break;
        for (int i = 0; i < TM; i++) {
            const int m = m0 + i;
            if (m < M) D[(size_t) n * M + m] = acc[i][t];
        }
    }
}
