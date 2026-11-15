// Panic handler for no_std Rust code linked into bionic: abort the process.
#![no_std]

use core::panic::PanicInfo;

extern "C" {
    fn abort() -> !;
}

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    // SAFETY: abort() takes no arguments and never returns.
    unsafe { abort() }
}
