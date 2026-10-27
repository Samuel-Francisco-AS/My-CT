// MYCT-DESKTOP-F2. Root-owned regular file, never setuid; authorization is installed separately.
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc != 2 || (std::strcmp(argv[1], "status") && std::strcmp(argv[1], "start") && std::strcmp(argv[1], "stop"))) {
        std::fputs("Only status|start|stop of vncserver@:1.service is supported\n", stderr);
        return 64;
    }
    const bool status = std::strcmp(argv[1], "status") == 0;
    if (!status && geteuid() != 0) {
        std::fputs("Requires explicitly installed restricted authorization\n", stderr);
        return 77;
    }
    // No shell, caller-selected unit, PATH lookup or inherited bus/pager environment.
    char* const env[] = {const_cast<char*>("PATH=/usr/bin"), const_cast<char*>("LANG=C"), nullptr};
    char* const query[] = {const_cast<char*>("/usr/bin/systemctl"), const_cast<char*>("--no-pager"),
        const_cast<char*>("show"), const_cast<char*>("--value"), const_cast<char*>("--property=ActiveState"),
        const_cast<char*>("vncserver@:1.service"), nullptr};
    char* const change[] = {const_cast<char*>("/usr/bin/systemctl"), const_cast<char*>("--no-block"),
        argv[1], const_cast<char*>("vncserver@:1.service"), nullptr};
    execve("/usr/bin/systemctl", status ? query : change, env);
    std::perror("systemctl");
    return 71;
}
