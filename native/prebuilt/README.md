Test builds for the phone, fetched with plain links while a change is being
tried (CI's artifact links expire within minutes). Built from this branch
with native/gfx/build.sh; CI's a17-mini carries the same programs.

- nalloc: the graphics allocator with ION heap selection
  (native.ion.heap, native.ion.cached).
