Test builds for the phone, fetched with plain links while a change is being
tried (CI's artifact links expire within minutes). Built from this branch;
CI's a17-mini carries the same programs.

- android17.apk: the app (native/app): Android 17's screen with touch,
  running Android 17 from its own files; imports a root from Termux.
- ncomposer: the composer with the viewer socket (@ndisplay).
- napexd.jar: napexd and ninput (the viewer's input, @ninput).
- nalloc: the graphics allocator with ION heap selection
  (native.ion.heap, native.ion.cached).
