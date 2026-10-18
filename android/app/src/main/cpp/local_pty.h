#pragma once
#include <atomic>
#include <cstddef>
#include <mutex>
#include <string>
#include <sys/types.h>

// Independent of JNI so the actual PTY lifecycle can also be exercised on Linux.
class LocalPty final {
public:
    LocalPty(const std::string& shell, const std::string& cwd, const std::string& startupFile = "");
    ~LocalPty();
    LocalPty(const LocalPty&) = delete;
    LocalPty& operator=(const LocalPty&) = delete;
    int readBytes(void* data, size_t size);
    void writeBytes(const void* data, size_t size);
    void resize(int columns, int rows, int width, int height);
    int waitForExit(); // exit code, or 128 + signal; exactly one cached waitpid
    void stop(); // idempotent, wakes blocked IO, signals only this session's groups
private:
    bool ready(short events);
    void reap();
    int master_ = -1, wake_[2] = {-1, -1};
    pid_t pid_ = -1;
    std::atomic<bool> stopped_{false};
    std::mutex childMutex_, waitMutex_;
    bool exited_ = false, reaped_ = false;
    int status_ = -1;
};
