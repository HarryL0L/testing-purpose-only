// Global allocator for no_std Rust code linked into bionic: malloc/free.
#![no_std]

use core::alloc::{GlobalAlloc, Layout};
use core::ptr;

extern "C" {
    fn malloc(size: usize) -> *mut u8;
    fn free(p: *mut u8);
    fn posix_memalign(out: *mut *mut u8, align: usize, size: usize) -> i32;
}

/// malloc() returns memory aligned for any fundamental type (16 bytes on 64-bit).
const MALLOC_ALIGN: usize = 2 * core::mem::size_of::<usize>();

struct LibcAlloc;

// SAFETY: malloc/posix_memalign return either null or a block of at least
// `layout.size()` bytes with the requested alignment, and free() accepts any
// pointer either returned.
unsafe impl GlobalAlloc for LibcAlloc {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if layout.align() <= MALLOC_ALIGN {
            // SAFETY: plain malloc call.
            unsafe { malloc(layout.size()) }
        } else {
            let mut p = ptr::null_mut();
            // SAFETY: `p` is a valid out pointer; align is a power of two >= sizeof(void*).
            if unsafe { posix_memalign(&mut p, layout.align(), layout.size()) } == 0 {
                p
            } else {
                ptr::null_mut()
            }
        }
    }

    unsafe fn dealloc(&self, p: *mut u8, _layout: Layout) {
        // SAFETY: `p` came from alloc() above.
        unsafe { free(p) }
    }
}

#[global_allocator]
static ALLOCATOR: LibcAlloc = LibcAlloc;
