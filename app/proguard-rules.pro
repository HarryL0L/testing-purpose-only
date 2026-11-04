# JNI entry points and the callback class used from native code
-keep class io.github.harryl0l.llamachat.engine.LlamaNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
