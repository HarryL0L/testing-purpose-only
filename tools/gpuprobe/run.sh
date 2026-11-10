#!/bin/sh
# Runs the GPU probe and writes report.txt (share that file). Full logs go to logs/.
#   sh run.sh         everything (about 15-40 min; keep the screen on, ideally charging)
#   sh run.sh quick   driver report + quick tests only (a few minutes)
cd "$(dirname "$0")"
MODE=${1:-full}
mkdir -p logs
REPORT=report.txt
: > "$REPORT"
say() { echo "$@" | tee -a "$REPORT"; }
section() { say ""; say "######## $1  ($(date +%H:%M:%S))"; }

section "phone"
say "model:   $(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.model 2>/dev/null)"
say "android: $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
say "soc:     $(getprop ro.soc.model 2>/dev/null) $(getprop ro.board.platform 2>/dev/null)"
say "kernel:  $(uname -r)"
say "mode:    $MODE"

section "vkprobe"
if [ "$MODE" = quick ]; then ./vkprobe --no-long; else ./vkprobe; fi 2>&1 | tee -a "$REPORT"

OPS_IMAGE="MUL_MAT,FLASH_ATTN_EXT,SOFT_MAX,ROPE,RMS_NORM,NORM,GELU,SILU,ADD,MUL,SCALE,GET_ROWS,CPY,CONT,CONV_2D,IM2COL,UPSCALE,PAD,CONCAT,GROUP_NORM"

section "ggml ops supported by Vulkan (counts)"
./test-backend-ops support -b Vulkan0 > logs/support.txt 2>&1
say "$(grep -ci 'not supported' logs/support.txt) op cases not supported on Vulkan (they fall back to the CPU; details: logs/support.txt)"
grep -i 'not supported' logs/support.txt | sed 's/(.*//' | sort | uniq -c | sort -rn | head -30 | tee -a "$REPORT"

section "correctness: Vulkan vs CPU"
if [ "$MODE" = quick ]; then OPS="MUL_MAT,FLASH_ATTN_EXT,SOFT_MAX,RMS_NORM"; else OPS="$OPS_IMAGE"; fi
say "ops: $OPS"
./test-backend-ops test -b Vulkan0 -o "$OPS" -j 4 > logs/test.txt 2>&1
say "exit code $?"
grep -aE "FAIL|tests passed|Backend|error|ERROR|assert|abort" logs/test.txt | sed 's/\x1b\[[0-9;]*m//g'  | grep -v "^ *$" | head -200 | tee -a "$REPORT"

section "performance: Vulkan"
if [ "$MODE" = quick ]; then
    PERF_MM='type_a=(f16|q8_0|q4_0),type_b=f32,m=4096,n=(1|512)'
else
    PERF_MM='type_a=(f16|q8_0|q5_K|q4_0)'
fi
./test-backend-ops perf -b Vulkan0 -o MUL_MAT -p "$PERF_MM" > logs/perf-vulkan-mm.txt 2>&1
grep -E "MUL_MAT|error|fail" logs/perf-vulkan-mm.txt | tee -a "$REPORT"
./test-backend-ops perf -b Vulkan0 -o FLASH_ATTN_EXT -p 'hsk=(64|72|128),' > logs/perf-vulkan-fa.txt 2>&1
grep -E "FLASH_ATTN|error|fail" logs/perf-vulkan-fa.txt | tee -a "$REPORT"

section "performance: CPU (for comparison)"
./test-backend-ops perf -b CPU -o MUL_MAT -p "$PERF_MM" > logs/perf-cpu-mm.txt 2>&1
grep -E "MUL_MAT|error|fail" logs/perf-cpu-mm.txt | tee -a "$REPORT"
./test-backend-ops perf -b CPU -o FLASH_ATTN_EXT -p 'hsk=(64|72|128),' > logs/perf-cpu-fa.txt 2>&1
grep -E "FLASH_ATTN|error|fail" logs/perf-cpu-fa.txt | tee -a "$REPORT"

section "done"
say "Share report.txt (and logs/ if asked)."
