#pragma once

// LlamaChat experimental Mali GPU backend for ggml (OpenCL, matrix multiplication only).
//
// A small, self-contained ggml backend that registers one GPU device and implements only
// GGML_OP_MUL_MAT for Q8_0 and F16 weights with F32 activations. Everything else stays on the CPU:
// ggml's scheduler sends the large matrix multiplications of prompt / image / video processing
// here and keeps the rest (and token-by-token generation) on the CPU.
//
// Its buffers are plain host memory, wrapped for the GPU with CL_MEM_USE_HOST_PTR: Mali GPUs share
// RAM with the CPU, so tensors are not copied between devices.

#include "ggml-backend.h"

#ifdef __cplusplus
extern "C" {
#endif

// Initializes OpenCL and returns the backend registration, or NULL when no suitable device exists.
// any_device: also accept non-GPU OpenCL devices (testing on a desktop CPU OpenCL like PoCL).
// Safe to call more than once (returns the same registration).
ggml_backend_reg_t ggml_backend_mali_reg(bool any_device);

// Human-readable status of the last init: device name and extensions, or why it failed.
const char * ggml_backend_mali_status(void);

// Minimum batch (tokens) for which matrix multiplications with weights in CPU memory are
// offloaded (default 32). Smaller batches (generation) stay on the CPU.
void ggml_backend_mali_set_min_batch(int n);

// Statistics since the last reset: number of matrix multiplications run on the GPU and their time.
void ggml_backend_mali_stats(int64_t * n_ops, double * gpu_ms);
void ggml_backend_mali_reset_stats(void);

#ifdef __cplusplus
}
#endif
