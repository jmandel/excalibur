cmake_minimum_required(VERSION 3.22.1)
project(kindle_wasm_runtime LANGUAGES CXX)

# NOTE: the prebuilt libwasmtime.so ships without a DT_SONAME. If we link it as a
# SHARED IMPORTED target via its absolute IMPORTED_LOCATION, lld records that full
# build-host path as the DT_NEEDED entry, e.g.
#   /home/runner/work/.../jniLibs/arm64-v8a/libwasmtime.so
# which does not exist on the device, so System.loadLibrary() crashes at runtime.
# Linking via a search directory (-L) + name (-l) instead makes lld record the
# plain basename "libwasmtime.so", which the Android linker resolves from the
# app's lib/<abi> dir where AGP packages the jniLibs.
add_library(kindle_wasm_runtime SHARED kindle_wasm_runtime.cpp)
target_compile_features(kindle_wasm_runtime PRIVATE cxx_std_17)
target_include_directories(kindle_wasm_runtime PRIVATE ${CMAKE_SOURCE_DIR}/../../../../third_party/wasmtime/android-aarch64/include)
target_link_directories(kindle_wasm_runtime PRIVATE ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
target_link_libraries(kindle_wasm_runtime PRIVATE wasmtime log)
