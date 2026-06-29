# Kindle Library Android

Native Android Kindle library manager. The preferred runtime path is now native Kotlin plus JNI/NDK Wasmtime running the same exnref static WASI CPython/calibre artifact used by the web app. The older WebView UI remains available as a fallback/debug screen while native UI is built out.

- Android document picker imports into app-private storage.
- Local converted AZW3 file storage.
- A tiny built-in HTTP server for the Kindle Experimental Browser.
- LAN/hotspot IP discovery and URL display.
- Experimental USB-OTG/MTP sync to an attached Kindle, with optional auto-sync
  when Android launches Excalibur for a Kindle USB attach event.

## UX notes

1. Open the app and choose the Kindle profile first. The 2024/12th-gen Paperwhite uses `kindle_oasis`, the closest calibre profile currently available in this source tree.
2. Import EPUB/MOBI/AZW files or load bundled samples.
3. After device confirmation, imported books are queued automatically and conversion status appears in the page-level log.
4. Keep the Android app open. On the Kindle, open one of the displayed `http://<ip>:<port>/` URLs.
5. The Kindle page is deliberately plain: latest AZW3 downloads are first, with one search box for title/author/tag filtering.
6. For USB sync, connect a Kindle with a USB-OTG cable, unlock it, allow file
   transfer, then use Settings -> Sync to Kindle. "Auto-sync when connected" is
   opt-in and only starts after Android opens Excalibur for a matching Kindle
   attach event.

## Hotspot / hostname ergonomics

Use numeric IP URLs. When Android is providing hotspot/tethering Wi-Fi, Kindle clients usually cannot resolve friendly names such as `kindle-library.local`; Android also does not provide a stable user-facing mDNS hostname for hotspot clients. The app enumerates IPv4 addresses and displays all candidates. On many devices the useful hotspot gateway address is in the `192.168.43.x`, `192.168.1.x`, `172.20.10.x`, or OEM-specific range.

## Build

This directory is a standard Kotlin/Gradle Android project. This VM has Android command-line tools installed under `/home/exedev/android-sdk`; `.bashrc` exports `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and SDK tools on `PATH`.

```bash
cd android-app
./gradlew assembleDebug
```

Validation performed here: `./gradlew assembleDebug` and web/Node runtime probes. Runtime assets are generated programmatically by `scripts/build_runtime_artifacts.py`; use `--android-precompile` to embed the aarch64 Wasmtime `.cwasm` artifact in the Android runtime zip.
