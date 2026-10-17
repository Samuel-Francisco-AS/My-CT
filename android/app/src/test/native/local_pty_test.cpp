#include "local_pty.h"
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <fstream>
#include <fcntl.h>
#include <future>
#include <iostream>
#include <thread>
#include <regex>
#include <unistd.h>
using namespace std::chrono_literals;

class Terminal {
public:
    LocalPty pty;
    std::mutex mutex;
    std::condition_variable changed;
    std::string output;
    std::thread reader;
    explicit Terminal(const std::string& cwd) : pty("/bin/sh", cwd), reader([this] {
        char bytes[8192];
        for (;;) {
            int count = pty.readBytes(bytes, sizeof(bytes));
            if (count < 0) break;
            { std::lock_guard<std::mutex> lock(mutex); output.append(bytes, count); }
            changed.notify_all();
        }
    }) {}
    ~Terminal() { pty.stop(); reader.join(); }
    void send(const std::string& text) { pty.writeBytes(text.data(), text.size()); }
    void expect(const std::string& text) {
        std::unique_lock<std::mutex> lock(mutex);
        if (!changed.wait_for(lock, 4s, [&] { return output.find(text) != std::string::npos; })) {
            std::cerr << "Missing " << text << " in " << output << std::endl;
            std::abort();
        }
    }
};

int main() {
    char pattern[] = "/tmp/myct-pty-XXXXXX";
    std::string root = mkdtemp(pattern);
    assert(!root.empty());
    std::filesystem::create_directory(root + "/ProjetoTeste");
    std::ofstream(root + "/ProjetoTeste/teste.c") << "MYCT_FILE_CONTENT\n";
    const auto fdCount = [] { return std::distance(std::filesystem::directory_iterator("/proc/self/fd"), std::filesystem::directory_iterator()); };
    auto before = fdCount();
    {
        int inherited = open((root + "/ProjetoTeste/teste.c").c_str(), O_RDONLY);
        assert(inherited >= 0);
        assert(dup2(inherited, 200) == 200); close(inherited); // Deliberately lacks CLOEXEC.
        Terminal local(root), remote(root); // Independent actual PTYs, no network or Fedora service.
        close(200);
        local.send("test ! -e /proc/$$/fd/200 && echo NO_APP_FD\n"); local.expect("NO_APP_FD\r\n");
        local.send("test -t 0 && test -t 1 && test -t 2 && echo REAL_TTY; pwd; echo $HOME; echo $TERM; echo $PATH\n");
        local.expect("REAL_TTY\r\n"); local.expect(root + "\r\n"); local.expect("xterm-256color\r\n");
        local.send("cd ProjetoTeste; pwd; /bin/ls; /bin/cat teste.c; echo PIPE_TEST | /bin/cat\n");
        local.expect(root + "/ProjetoTeste\r\n"); local.expect("MYCT_FILE_CONTENT\r\n"); local.expect("PIPE_TEST\r\n");
        local.pty.resize(57, 13, 456, 208);
        local.send("/bin/stty size\n"); local.expect("13 57\r\n");
        local.send("echo WAITING; read value; echo INPUT_$value\n"); local.expect("WAITING\r\n");
        local.send("hello\n"); local.expect("INPUT_hello\r\n");
        local.send("echo SLEEPING; /bin/sleep 20; echo INTERRUPTED_$?\n"); local.expect("SLEEPING\r\n");
        std::this_thread::sleep_for(100ms); local.send(std::string(1, 3));
        local.send("echo CTRL_C_$?\n"); local.expect("CTRL_C_130\r\n");
        local.send("exit 7\n"); assert(local.pty.waitForExit() == 7); assert(local.pty.waitForExit() == 7);
        local.pty.stop(); local.pty.stop();
        remote.send("echo SURVIVED\n"); remote.expect("SURVIVED\r\n");
    }
    {
        pid_t background = -1;
        {
            Terminal jobs(root);
            jobs.send("/bin/sleep 20 & echo BG_$!\n");
            std::unique_lock<std::mutex> lock(jobs.mutex);
            std::smatch match;
            assert(jobs.changed.wait_for(lock, 3s, [&] { return std::regex_search(jobs.output, match, std::regex("\\r\\nBG_([0-9]+)\\r\\n")); }));
            background = std::stoi(match[1]);
        }
        // Orphans may briefly remain as zombies until init reaps them; none may keep running.
        bool stopped = false;
        for (int i = 0; i < 200 && !stopped; ++i) {
            std::ifstream stat("/proc/" + std::to_string(background) + "/stat");
            std::string record; std::getline(stat, record);
            stopped = record.empty() || record.substr(record.rfind(')') + 2, 1) == "Z";
            if (!stopped) std::this_thread::sleep_for(5ms);
        }
        assert(stopped);
    }
    {
        LocalPty blocked("/bin/sh", root);
        auto reading = std::async(std::launch::async, [&] { char bytes[8192]; while (blocked.readBytes(bytes, sizeof(bytes)) >= 0) {} });
        auto waiting = std::async(std::launch::async, [&] { return blocked.waitForExit(); });
        blocked.stop();
        assert(reading.wait_for(2s) == std::future_status::ready);
        assert(waiting.wait_for(2s) == std::future_status::ready);
        assert(waiting.get() == 137);
    }
    for (int i = 0; i < 8; ++i) {
        bool failed = false;
        try { LocalPty missing("/not-a-myct-shell", root); } catch (const std::system_error&) { failed = true; }
        assert(failed);
        failed = false;
        try { LocalPty missing("/bin/sh", root + "/missing"); } catch (const std::system_error&) { failed = true; }
        assert(failed);
        LocalPty pty("/bin/sh", root);
        try { pty.resize(0, 0, 0, 0); assert(false); } catch (const std::invalid_argument&) {}
        pty.stop(); assert(pty.waitForExit() == 137);
    }
    assert(before == fdCount());
    std::filesystem::remove_all(root); // Only the test's mkdtemp fixture.
    std::cout << "PASS: real PTY, cwd/env, pipes/read, resize, Ctrl+C, exit, isolated close, blocked read/wait, errors, FD/reap lifecycle\n";
}
