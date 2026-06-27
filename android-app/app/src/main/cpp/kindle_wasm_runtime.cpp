#include <jni.h>
#include <android/log.h>
#include <wasm.h>
#include <wasi.h>
#include <wasmtime/config.h>
#include <wasmtime/engine.h>
#include <wasmtime/error.h>
#include <wasmtime/extern.h>
#include <wasmtime/func.h>
#include <wasmtime/instance.h>
#include <wasmtime/linker.h>
#include <wasmtime/module.h>
#include <wasmtime/store.h>
#include <algorithm>
#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "KindleWasmtime"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct RunResult { int code = 0; std::string stdout_text; std::string stderr_text; std::string error; long compile_ms = 0; long run_ms = 0; bool used_precompiled = false; };
struct Capture { std::string *target; };

static ptrdiff_t write_capture(void *data, const unsigned char *bytes, size_t len) {
    auto *cap = reinterpret_cast<Capture *>(data);
    cap->target->append(reinterpret_cast<const char *>(bytes), len);
    return static_cast<ptrdiff_t>(len);
}
static void delete_capture(void *data) { delete reinterpret_cast<Capture *>(data); }
static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}
static bool read_file(const std::string &path, std::vector<uint8_t> &out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    f.seekg(0, std::ios::end); auto n = f.tellg(); f.seekg(0, std::ios::beg);
    if (n < 0) return false;
    out.resize(static_cast<size_t>(n));
    if (!out.empty()) f.read(reinterpret_cast<char *>(out.data()), out.size());
    return true;
}
static std::string wasm_error_message(wasmtime_error_t *err) {
    if (!err) return {};
    wasm_name_t msg; wasmtime_error_message(err, &msg);
    std::string s(msg.data, msg.size);
    wasm_byte_vec_delete(&msg);
    return s;
}
static std::string trap_message(wasm_trap_t *trap) {
    if (!trap) return {};
    wasm_message_t msg; wasm_trap_message(trap, &msg);
    std::string s(msg.data, msg.size);
    wasm_byte_vec_delete(&msg);
    return s;
}
static void append_error(RunResult &r, const std::string &where, wasmtime_error_t *err) {
    r.code = 1;
    r.error += where + ": " + wasm_error_message(err) + "\n";
    wasmtime_error_delete(err);
}
static bool preopen(wasi_config_t *wasi, const std::string &host, const char *guest, bool write = true) {
    auto d = WASMTIME_WASI_DIR_PERMS_READ | (write ? WASMTIME_WASI_DIR_PERMS_WRITE : 0);
    auto f = WASMTIME_WASI_FILE_PERMS_READ | (write ? WASMTIME_WASI_FILE_PERMS_WRITE : 0);
    return wasi_config_preopen_dir(wasi, host.c_str(), guest, d, f);
}
static RunResult run_wasmtime(const std::string &wasm_path, const std::string &cwasm_path, const std::string &runtime_root, const std::string &work_dir, bool prefer_precompiled) {
    RunResult r;
    auto t0 = std::chrono::steady_clock::now();
    wasm_config_t *config = wasm_config_new();
    wasmtime_config_wasm_exceptions_set(config, true);
    wasmtime_config_cranelift_opt_level_set(config, WASMTIME_OPT_LEVEL_SPEED);
    wasm_engine_t *engine = wasm_engine_new_with_config(config);
    if (!engine) { r.code = 1; r.error = "wasm_engine_new_with_config failed"; return r; }

    wasmtime_module_t *module = nullptr;
    if (prefer_precompiled && !cwasm_path.empty()) {
        wasmtime_error_t *err = wasmtime_module_deserialize_file(engine, cwasm_path.c_str(), &module);
        if (!err && module) r.used_precompiled = true;
        else { if (err) wasmtime_error_delete(err); module = nullptr; }
    }
    if (!module) {
        std::vector<uint8_t> wasm;
        if (!read_file(wasm_path, wasm)) { r.code = 1; r.error = "failed to read wasm: " + wasm_path; wasm_engine_delete(engine); return r; }
        wasmtime_error_t *err = wasmtime_module_new(engine, wasm.data(), wasm.size(), &module);
        if (err) { append_error(r, "compile wasm", err); wasm_engine_delete(engine); return r; }
    }
    auto t1 = std::chrono::steady_clock::now();
    r.compile_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    wasmtime_store_t *store = wasmtime_store_new(engine, nullptr, nullptr);
    wasmtime_context_t *ctx = wasmtime_store_context(store);
    wasi_config_t *wasi = wasi_config_new();
    const char *argv[] = {"python.wasm", "-S", "/work/probe.py"};
    wasi_config_set_argv(wasi, 3, argv);
    const char *names[] = {"PYTHONPATH", "PYTHONDONTWRITEBYTECODE", "PYTHONHOME", "PYTHONTZPATH", "HOME", "XDG_CONFIG_HOME"};
    const char *values[] = {"/build/lib.wasi-wasm32-3.12:/Lib:/third_party_site:/experiments:/third_party/calibre/src", "1", "/", "/usr/share/zoneinfo", "/work", "/work/.config"};
    wasi_config_set_env(wasi, 6, names, values);
    wasi_config_set_stdout_custom(wasi, write_capture, new Capture{&r.stdout_text}, delete_capture);
    wasi_config_set_stderr_custom(wasi, write_capture, new Capture{&r.stderr_text}, delete_capture);
    preopen(wasi, runtime_root + "/wasi", "/", false);
    preopen(wasi, runtime_root + "/experiments", "/experiments", false);
    preopen(wasi, runtime_root + "/third_party", "/third_party", false);
    preopen(wasi, runtime_root + "/wasi/third_party_site", "/third_party_site", false);
    preopen(wasi, work_dir, "/work", true);
    wasmtime_error_t *err = wasmtime_context_set_wasi(ctx, wasi);
    if (err) { append_error(r, "set wasi", err); wasmtime_store_delete(store); wasmtime_module_delete(module); wasm_engine_delete(engine); return r; }
    wasmtime_linker_t *linker = wasmtime_linker_new(engine);
    err = wasmtime_linker_define_wasi(linker);
    if (err) { append_error(r, "define wasi", err); wasmtime_linker_delete(linker); wasmtime_store_delete(store); wasmtime_module_delete(module); wasm_engine_delete(engine); return r; }
    wasmtime_instance_t instance; wasm_trap_t *trap = nullptr;
    err = wasmtime_linker_instantiate(linker, ctx, module, &instance, &trap);
    if (err) { append_error(r, "instantiate", err); }
    else if (trap) { r.code = 1; r.error = "instantiate trap: " + trap_message(trap); wasm_trap_delete(trap); }
    else {
        wasmtime_extern_t item;
        if (!wasmtime_instance_export_get(ctx, &instance, "_start", 6, &item) || item.kind != WASMTIME_EXTERN_FUNC) {
            r.code = 1; r.error = "_start export not found";
        } else {
            err = wasmtime_func_call(ctx, &item.of.func, nullptr, 0, nullptr, 0, &trap);
            if (err) {
                int status = 0;
                if (wasmtime_error_exit_status(err, &status) && status == 0) { r.code = 0; wasmtime_error_delete(err); }
                else append_error(r, "_start", err);
            } else if (trap) { r.code = 1; r.error = "_start trap: " + trap_message(trap); wasm_trap_delete(trap); }
            wasmtime_extern_delete(&item);
        }
    }
    auto t2 = std::chrono::steady_clock::now();
    r.run_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    wasmtime_linker_delete(linker); wasmtime_store_delete(store); wasmtime_module_delete(module); wasm_engine_delete(engine);
    return r;
}
static jstring result_json(JNIEnv *env, const RunResult &r) {
    auto esc = [](const std::string &s){ std::string o; o.reserve(s.size()+16); for(char c: s){ switch(c){ case '\\': o += "\\\\"; break; case '"': o += "\\\""; break; case '\n': o += "\\n"; break; case '\r': o += "\\r"; break; case '\t': o += "\\t"; break; default: if ((unsigned char)c < 32) o += ' '; else o += c; } } return o; };
    std::ostringstream ss;
    ss << "{\"exitCode\":" << r.code << ",\"compileMs\":" << r.compile_ms << ",\"runMs\":" << r.run_ms << ",\"usedPrecompiled\":" << (r.used_precompiled ? "true" : "false") << ",\"stdout\":\"" << esc(r.stdout_text) << "\",\"stderr\":\"" << esc(r.stderr_text) << "\",\"error\":\"" << esc(r.error) << "\"}";
    return env->NewStringUTF(ss.str().c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_exe_kindleconverter_wasmtime_WasmtimeRuntime_nativeRunPython(JNIEnv *env, jobject, jstring wasm, jstring cwasm, jstring root, jstring work, jboolean prefer) {
    auto r = run_wasmtime(jstr(env, wasm), jstr(env, cwasm), jstr(env, root), jstr(env, work), prefer == JNI_TRUE);
    ALOGI("run done code=%d compile=%ld run=%ld precompiled=%d", r.code, r.compile_ms, r.run_ms, r.used_precompiled ? 1 : 0);
    return result_json(env, r);
}
