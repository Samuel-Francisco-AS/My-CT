# Offline C worker — MYCT-L1C

**F1:** RC1/code 26 failed physically with status 139 on the Moto G06. RC2/code
27 adds stage/resource diagnostics and correct native-failure classification;
the native cause is unproven, RLIMIT_AS remains 384 MiB, physical gate pending.
See `docs/MYCT-L1C-F1-RELATORIO.md`.

The APK ships **WCPL 1.04** (real C parser/compiler/linker, headers and a small
libc) and **WAMR 2.4.4**, classic interpreter only. They run on Android in a
fixed native PIE worker installed with the APK. The selected source is compiled
**on the phone** to WebAssembly/WASI, then interpreted in a new worker process.
No user ELF, executable-memory generation, JIT, AOT, downloaded components,
Termux application, shell command, Fedora or remote compiler is involved.

## Provenance and licenses

`upstream/manifest.json` records full commits and SHA-256 of source archives:

- WCPL `458a542ca81fa7a8fd8c8eed38a32dce8ed45135`, MIT;
  https://github.com/false-schemers/wcpl.
- WAMR tag WAMR-2.4.4, `8c18e3f68b16c4bcaf05996b2636f6ed2b4cf629`,
  Apache-2.0 WITH LLVM-exception; WASI sandbox primitives BSD-2-Clause / CC0;
  https://github.com/bytecodealliance/wasm-micro-runtime.
- WCPL libc includes wasi-libc-derived routines and Sun fdlibm code. Original
  notices are preserved in the source archive and APK `assets/licenses/offline-c/`.

`prepare.py` verifies archive hashes before offline extraction. The only WCPL
adaptation changes the embedded stdio object's **stdin/stdout default flags to
unbuffered**, so `printf("Numero: ")` becomes visible before `scanf` blocks on
pipes. The script checks the original flags, patches two bytes in its data
segment, recompresses that object into `l.c`, and updates the corresponding libc
source/object in the prepared tree. F1 additionally injects fixed diagnostic calls at native compiler entry,
initialization, parsing, linking and WASM encoding boundaries; the calls receive
no source/path/token/input. Compiler behavior and generated code are otherwise
unchanged; headers and all other libc bytes remain untouched. No modification to the user's source.

WAMR is built without AOT/JIT/fast-JIT/SIMD/threads or hardware signal-based
bounds checks. The classic interpreter performs software bounds checks.
There are no filesystem preopens, custom host APIs, inherited environment or
allowed network addresses. A WASI program sees console descriptors only.
This is a small console environment, not Linux/POSIX or `/system/bin/sh`.

## Android packaging and limits

`build.py` uses existing NDK 28.2.13676358 + CMake 3.22.1, builds and strips
arm64-v8a / armeabi-v7a / x86_64. It performs no network access. Gradle's
`buildOfflineC` task places outputs under `app/build/generated/offlineC/jniLibs`.
`useLegacyPackaging=true` makes PackageManager extract native code to the
installation's nativeLibraryDir. The PIE is named **libmyct-c-worker.so** for
native-code packaging; it is an installed fixed worker, not a user's shared
library. Kotlin calls ProcessBuilder with an argv list and that installed path.
It never invokes a shell or executes anything from filesDir/cacheDir.
This packaging must still pass the supplied Android instrumented tests and the
physical Moto gate; cross-compilation alone does not prove permission to exec.

One compile/run at a time. Each phase has a fresh process. Kotlin controls
30 s compile / 60 s run wall time (including waiting for keyboard input).
Native fallback: 90 s alarm, 30 s CPU, 384 MiB address space, 16 MiB output-file
limit, core dumps disabled. WASM linear memory: 256 pages = 16 MiB;
interpreter stack: 128 KiB. Cancellation force-kills the current process,
including blocked stdin and infinite loops. PDEATHSIG kills the worker when its
Android parent dies. No FGS or background execution guarantee is added.
Console output is bounded to 65,536 characters; exceeding it stops the worker.
Native crashes/traps/limits affect the child, preserving the Sora buffer/source.

Artifacts live in `filesDir/ide-local/compilacoes/<UUID>/`: `programa.c` is the
exact saved-source snapshot, `origem.txt` records workspace path + SHA-256 and
`programa.wasm` is the new module. Projects are not modified. Previous artifacts
are never selected for execution after a compile error. Generated files are
retained for inspection; they are private app data and consume disk until
removed by the owner, like other app files. No automatic destructive cleanup.

## C subset

Supports the required console gate: `int main(void)`, integer arithmetic,
if/else, for/while, functions, `#include <stdio.h>`, printf/puts/scanf, diagnostics
with line/column/caret and program exit status. WCPL supports a documented
traditional-C subset with some later features (declarations in for, stdint,
stdbool, numeric/string macros). **It is not GCC/Clang or a full C99 compiler.**

Notable upstream limits: array-to-pointer conversions often require explicit
`&array[0]`; null pointers require NULL rather than implicit 0; pointer-to-bool
may require explicit `!= NULL`; scalar global addresses, local static variables,
free-form goto/switch, bitfields and setjmp/longjmp are restricted/unsupported.
See the pinned upstream README for the complete list. wasm32 uses 32-bit
pointers/int/long; long long is 64-bit. C++ is not compiled.

A run compiles **one translation unit** and the built-in headers/libraries;
project headers/extra source files/build systems are not copied into the build
snapshot. The internal filename `programa.c` avoids the upstream collision with
library module names such as `stdio.c`. Diagnostics refer to `programa.c`;
the console shows its actual workspace origin, with identical line numbers.
Standard headers include stdio, stdlib, string, ctype, errno, assert, limits,
float, stdarg, stddef, stdint, stdbool, inttypes, math, time and locale; POSIX-like
headers exist upstream, but filesystem/network capabilities are not granted.
Exit status is the low 8 bits at the OS boundary; WAMR prints the full WASI
status. stdin is submitted by line; EOF is explicit, not raw terminal input.
Console content is selectable; there is no terminal ANSI/TUI emulation.

## Rebuild and verify (no downloads after provisioning)

From repository root, with JDK/SDK/NDK already installed:

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug --offline --console=plain
```

Host proof with the exact same compiler/interpreter/worker code:

```sh
python3 android/offline-c/prepare.py android/app/build/offline-c/vendor
/home/sam/Android/Sdk/cmake/3.22.1/bin/cmake -S android/offline-c \
  -B android/app/build/offline-c/host -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/home/sam/Android/Sdk/cmake/3.22.1/bin/ninja \
  -DMYCT_VENDOR="$PWD/android/app/build/offline-c/vendor" -DCMAKE_BUILD_TYPE=Release
/home/sam/Android/Sdk/cmake/3.22.1/bin/cmake --build android/app/build/offline-c/host -j 2
python3 android/offline-c/tests/test_worker.py android/app/build/offline-c/host/libmyct-c-worker.so
MYCT_TEST_OFFLINE_C="$PWD/android/app/build/offline-c/host/libmyct-c-worker.so" \
  JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:testDebugUnitTest --offline --console=plain
```

Host proof is not Android execution/performance evidence. Instrumentation
`OfflineCInstrumentedTest` checks APK-installed exec permission, real compile,
stdio, invalid source, recompile, scanf prompt-before-input and killing an
infinite loop. It is compiled into the test APK; do not mark it executed without
an authorized Android device/test environment. The owner's manual A–F gate in
`docs/MYCT-L1C-RELATORIO.md` requires explicit confirmation on the Moto G06.
