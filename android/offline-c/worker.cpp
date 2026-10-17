// My-CT L1C: each compile/run gets a fresh, killable native worker process.
#include "wasm_export.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
extern "C" int wcpl_main(int, char **);

// Fixed stage names and numeric resource data only: no source, paths, argv,
// environment, addresses or user input. Use stack buffers and write(), including
// the first marker before resource limits/stdio/any compiler allocation.
static void diagnostic(const char *text) {
    size_t n = std::strlen(text);
    while (n) {
        ssize_t sent = write(STDERR_FILENO, text, n);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) break;
        text += sent; n -= static_cast<size_t>(sent);
    }
}
static void stage(const char *name) {
    diagnostic("[myct-worker:v2] stage="); diagnostic(name); diagnostic("\n");
}
extern "C" void myct_wcpl_stage(const char *name) { stage(name); }
static unsigned long long field(const char *status, const char *key) {
    const char *p = std::strstr(status, key);
    if (!p) return 0;
    p += std::strlen(key);
    while (*p == ' ' || *p == '\t') ++p;
    unsigned long long result = 0;
    while (*p >= '0' && *p <= '9') result = result * 10 + (*p++ - '0');
    return result;
}
static void resources(const char *stage) {
    char status[4096]{}, line[384]{};
    int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { ssize_t n = read(fd, status, sizeof(status)-1); (void)n; close(fd); }
    rlimit space{}, stack{};
    getrlimit(RLIMIT_AS, &space); getrlimit(RLIMIT_STACK, &stack);
    std::snprintf(line, sizeof(line),
        "[myct-worker:v2] stage=%s vm_kib=%llu rss_kib=%llu stack_kib=%llu as_limit=%llu stack_limit=%llu\n",
        stage, field(status,"VmSize:"), field(status,"VmRSS:"), field(status,"VmStk:"),
        static_cast<unsigned long long>(space.rlim_cur), static_cast<unsigned long long>(stack.rlim_cur));
    diagnostic(line);
}

static void limit(int resource, rlim_t value) {
    rlimit r{value, value};
    if (setrlimit(resource, &r)) { std::perror("Limite do worker"); std::exit(125); }
}
static int run(const char *path) {
    stage("wasm-read");
    char error[256]{};
    FILE *f = std::fopen(path, "rb");
    if (!f) { std::perror("Artefato WASM"); return 125; }
    struct stat st{};
    if (fstat(fileno(f), &st) || st.st_size < 8 || st.st_size > 16 * 1024 * 1024) {
        std::fclose(f); std::fputs("Artefato WASM inválido ou maior que 16 MiB.\n", stderr); return 125;
    }
    std::vector<uint8_t> bytes(static_cast<size_t>(st.st_size));
    bool read = std::fread(bytes.data(), 1, bytes.size(), f) == bytes.size();
    std::fclose(f);
    if (!read || std::memcmp(bytes.data(), "\0asm\1\0\0\0", 8)) {
        std::fputs("Artefato sem formato WebAssembly MVP.\n", stderr); return 125;
    }
    stage("wamr-init");
    if (!wasm_runtime_init()) { std::fputs("Falha ao iniciar WAMR.\n", stderr); return 125; }
    stage("wasm-load");
    auto module = wasm_runtime_load(bytes.data(), bytes.size(), error, sizeof(error));
    if (!module) { std::fprintf(stderr, "Erro WASM: %s\n", error); wasm_runtime_destroy(); return 125; }
    char name[] = "programa"; char *args[] = {name};
    // No filesystem preopens, environment, network addresses or custom host APIs.
    wasm_runtime_set_wasi_args_ex(module, nullptr, 0, nullptr, 0, nullptr, 0, args, 1, 0, 1, 2);
    InstantiationArgs2 *config = nullptr;
    if (!wasm_runtime_instantiation_args_create(&config)) { wasm_runtime_unload(module); wasm_runtime_destroy(); return 125; }
    wasm_runtime_instantiation_args_set_default_stack_size(config, 128 * 1024);
    wasm_runtime_instantiation_args_set_max_memory_pages(config, 256); // 16 MiB linear memory
    stage("wasm-instantiate");
    auto instance = wasm_runtime_instantiate_ex2(module, config, error, sizeof(error));
    wasm_runtime_instantiation_args_destroy(config);
    if (!instance) {
        std::fprintf(stderr, "Memória/instância WASM: %s\n", error);
        wasm_runtime_unload(module); wasm_runtime_destroy(); return 125;
    }
    stage("wasm-execute");
    bool ok = wasm_application_execute_main(instance, 0, nullptr);
    unsigned code = wasm_runtime_get_wasi_exit_code(instance);
    if (!ok) std::fprintf(stderr, "Erro de execução WASM: %s\n", wasm_runtime_get_exception(instance));
    // Separate metadata from stdout/stderr. An ordinary program returning 125
    // must remain distinguishable from a runtime failure in the console.
    std::fprintf(stderr, "\n[myct-wasm: %s; código=%u]\n", ok ? "encerrado" : "falha", code);
    stage("wasm-finished");
    wasm_runtime_deinstantiate(instance); wasm_runtime_unload(module); wasm_runtime_destroy();
    return ok ? static_cast<int>(code & 255) : 125;
}
int main(int argc, char **argv) {
#if defined(__aarch64__)
    diagnostic("[myct-worker:v2] stage=entry abi=arm64-v8a\n");
#elif defined(__arm__)
    diagnostic("[myct-worker:v2] stage=entry abi=armeabi-v7a\n");
#elif defined(__x86_64__)
    diagnostic("[myct-worker:v2] stage=entry abi=x86_64\n");
#endif
    resources("before-limits");
    const pid_t parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent) {
        diagnostic("[myct-worker:v2] init-error=parent-watch\n"); return 125;
    }
    limit(RLIMIT_CORE, 0);
    limit(RLIMIT_AS, 384ULL * 1024 * 1024);
    limit(RLIMIT_FSIZE, 16 * 1024 * 1024);
    limit(RLIMIT_CPU, 30);
    resources("after-limits");
    alarm(90); // hard fallback, even if the Android controller dies/stalls
    unsetenv("WCPL_INCLUDE_PATH"); unsetenv("WCPL_LIBRARY_PATH");
    setvbuf(stdout, nullptr, _IONBF, 0); setvbuf(stderr, nullptr, _IONBF, 0);
    if (argc == 3 && !std::strcmp(argv[1], "run")) return run(argv[2]);
    if (argc == 4 && !std::strcmp(argv[1], "compile")) {
        char option[] = "-o", quiet[] = "-q", name[] = "WCPL";
        char *options[] = {name, quiet, option, argv[3], argv[2], nullptr};
        stage("wcpl-enter");
        const int result = wcpl_main(5, options);
        resources("wcpl-return");
        return result;
    }
    std::fputs("Uso interno: compile fonte.c artefato.wasm | run artefato.wasm\n", stderr);
    return 125;
}
