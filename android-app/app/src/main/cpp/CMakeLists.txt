cmake_minimum_required(VERSION 3.22.1)
project(kindle_wasm_runtime LANGUAGES CXX)

add_library(wasmtime SHARED IMPORTED)
set_target_properties(wasmtime PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwasmtime.so)

add_library(kindle_wasm_runtime SHARED kindle_wasm_runtime.cpp)
target_compile_features(kindle_wasm_runtime PRIVATE cxx_std_17)
target_include_directories(kindle_wasm_runtime PRIVATE ${CMAKE_SOURCE_DIR}/../../../../third_party/wasmtime/android-aarch64/include)
target_link_libraries(kindle_wasm_runtime PRIVATE wasmtime log)
