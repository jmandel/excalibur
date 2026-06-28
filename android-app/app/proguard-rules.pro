# JNI: the native symbols in libkindle_wasm_runtime.so are bound by exact
# class + method name (Java_dev_exe_kindleconverter_wasmtime_WasmtimeRuntime_*),
# and onNativeLine is called back from native. Keep the class and its members so
# R8 doesn't rename them.
-keep class dev.exe.kindleconverter.wasmtime.WasmtimeRuntime { *; }

# Room ships its own consumer rules; Compose and androidx.annotation @Keep are
# handled by their bundled rules. Enums used by Room TypeConverters keep their
# values()/valueOf() via the default optimized android rules.
