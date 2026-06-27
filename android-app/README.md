# Kindle Library Android

Native Android shell for the browser calibre/Pyodide converter. It is intentionally a WebView-hosted app so the same WASM conversion pipeline can run on Android while native code handles:

- Android document picker imports into app-private storage.
- Local converted AZW3 file storage.
- A tiny built-in HTTP server for the Kindle Experimental Browser.
- LAN/hotspot IP discovery and URL display.

## UX notes

1. Open the app and choose the Kindle profile first. The 2024/12th-gen Paperwhite uses `kindle_oasis`, the closest calibre profile currently available in this source tree.
2. Import EPUB/MOBI/AZW files or load bundled samples.
3. After device confirmation, imported books are queued automatically and conversion status appears in the page-level log.
4. Keep the Android app open. On the Kindle, open one of the displayed `http://<ip>:<port>/` URLs.
5. The Kindle page is deliberately plain: latest AZW3 downloads are first, with one search box for title/author/tag filtering.

## Hotspot / hostname ergonomics

Use numeric IP URLs. When Android is providing hotspot/tethering Wi-Fi, Kindle clients usually cannot resolve friendly names such as `kindle-library.local`; Android also does not provide a stable user-facing mDNS hostname for hotspot clients. The app enumerates IPv4 addresses and displays all candidates. On many devices the useful hotspot gateway address is in the `192.168.43.x`, `192.168.1.x`, `172.20.10.x`, or OEM-specific range.

## Build

This directory is a standard Gradle Android project. Build on a machine with JDK + Android SDK:

```bash
cd android-app
./gradlew assembleDebug
```

Validation performed here: `javac -cp /usr/lib/android-sdk/platforms/android-23/android.jar app/src/main/java/dev/exe/kindleconverter/MainActivity.java` and `node --check` on the embedded app script. A full APK build still needs a normal Android SDK installation with the Gradle-selected compile SDK.
