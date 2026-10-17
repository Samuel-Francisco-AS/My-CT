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
extern "C" int wcpl_main(int, char **);

static void limit(int resource, rlim_t value) {
    rlimit r{value, value};
    if (setrlimit(resource, &r)) { std::perror("Limite do worker"); std::exit(125); }
}
static int run(const char *path) {
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
    if (!wasm_runtime_init()) { std::fputs("Falha ao iniciar WAMR.\n", stderr); return 125; }
    auto module = wasm_runtime_load(bytes.data(), bytes.size(), error, sizeof(error));
    if (!module) { std::fprintf(stderr, "Erro WASM: %s\n", error); wasm_runtime_destroy(); return 125; }
    char name[] = "programa"; char *args[] = {name};
    // No filesystem preopens, environment, network addresses or custom host APIs.
    wasm_runtime_set_wasi_args_ex(module, nullptr, 0, nullptr, 0, nullptr, 0, args, 1, 0, 1, 2);
    InstantiationArgs2 *config = nullptr;
    if (!wasm_runtime_instantiation_args_create(&config)) { wasm_runtime_unload(module); wasm_runtime_destroy(); return 125; }
    wasm_runtime_instantiation_args_set_default_stack_size(config, 128 * 1024);
    wasm_runtime_instantiation_args_set_max_memory_pages(config, 256); // 16 MiB linear memory
    auto instance = wasm_runtime_instantiate_ex2(module, config, error, sizeof(error));
    wasm_runtime_instantiation_args_destroy(config);
    if (!instance) {
        std::fprintf(stderr, "Memória/instância WASM: %s\n", error);
        wasm_runtime_unload(module); wasm_runtime_destroy(); return 125;
    }
    bool ok = wasm_application_execute_main(instance, 0, nullptr);
    unsigned code = wasm_runtime_get_wasi_exit_code(instance);
    if (!ok) std::fprintf(stderr, "Erro de execução WASM: %s\n", wasm_runtime_get_exception(instance));
    // Separate metadata from stdout/stderr. An ordinary program returning 125
    // must remain distinguishable from a runtime failure in the console.
    std::fprintf(stderr, "\n[myct-wasm: %s; código=%u]\n", ok ? "encerrado" : "falha", code);
    wasm_runtime_deinstantiate(instance); wasm_runtime_unload(module); wasm_runtime_destroy();
    return ok ? static_cast<int>(code & 255) : 125;
}
int main(int argc, char **argv) {
    const pid_t parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent) return 125;
    limit(RLIMIT_CORE, 0);
    limit(RLIMIT_AS, 384ULL * 1024 * 1024);
    limit(RLIMIT_FSIZE, 16 * 1024 * 1024);
    limit(RLIMIT_CPU, 30);
    alarm(90); // hard fallback, even if the Android controller dies/stalls
    unsetenv("WCPL_INCLUDE_PATH"); unsetenv("WCPL_LIBRARY_PATH");
    setvbuf(stdout, nullptr, _IONBF, 0); setvbuf(stderr, nullptr, _IONBF, 0);
    if (argc == 3 && !std::strcmp(argv[1], "run")) return run(argv[2]);
    if (argc == 4 && !std::strcmp(argv[1], "compile")) {
        char option[] = "-o", quiet[] = "-q", name[] = "WCPL";
        char *options[] = {name, quiet, option, argv[3], argv[2], nullptr};
        return wcpl_main(5, options);
    }
    std::fputs("Uso interno: compile fonte.c artefato.wasm | run artefato.wasm\n", stderr);
    return 125;
}
