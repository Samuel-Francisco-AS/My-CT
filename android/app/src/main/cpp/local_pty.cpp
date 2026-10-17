#include "local_pty.h"
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <stdexcept>
#include <system_error>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

namespace {
void checked(bool ok, const char* operation) {
    if (!ok) throw std::system_error(errno, std::generic_category(), operation);
}
void closeFd(int& fd) { if (fd >= 0) { close(fd); fd = -1; } }
void closeOtherFds(int keep, long maxFd) {
    // Portable to API 26; do not probe new syscalls that older app seccomp may reject.
    // The limit is captured before fork. No allocation, /proc scan or inherited-FD race.
    for (int fd = 3; fd < maxFd; ++fd) if (fd != keep) close(fd);
}
// Called after fork: only async-signal-safe calls, no JNI, logging or allocation.
[[noreturn]] void childError(int fd) {
    int error = errno;
    ssize_t result;
    do { result = write(fd, &error, sizeof(error)); } while (result < 0 && errno == EINTR);
    _exit(127);
}
}

LocalPty::LocalPty(const std::string& shell, const std::string& cwd) {
    int slave = -1, errorPipe[2] = {-1, -1};
    try {
        winsize size{24, 80, 0, 0};
        if (openpty(&master_, &slave, nullptr, nullptr, &size) != 0) {
            // Bionic closes partial allocations but leaves their numbers in the out arguments.
            master_ = -1; slave = -1;
            checked(false, "openpty");
        }
        termios settings{};
        checked(tcgetattr(slave, &settings) == 0, "tcgetattr");
        settings.c_iflag |= ICRNL | IXON | IUTF8;
        settings.c_oflag |= OPOST | ONLCR;
        settings.c_lflag |= ISIG | ICANON | ECHO | ECHOE | ECHOK;
        settings.c_cc[VINTR] = 3; settings.c_cc[VEOF] = 4; settings.c_cc[VERASE] = 127;
        checked(tcsetattr(slave, TCSANOW, &settings) == 0, "tcsetattr");
        checked(fcntl(master_, F_SETFD, FD_CLOEXEC) == 0, "master CLOEXEC");
        checked(fcntl(slave, F_SETFD, FD_CLOEXEC) == 0, "slave CLOEXEC");
        checked(fcntl(master_, F_SETFL, O_NONBLOCK) == 0, "master nonblocking");
        checked(pipe2(wake_, O_CLOEXEC | O_NONBLOCK) == 0, "wake pipe");
        checked(pipe2(errorPipe, O_CLOEXEC) == 0, "exec error pipe");
        // Don't inherit app sockets/credentials, including FDs opened concurrently by SSH.
        long maxFd = sysconf(_SC_OPEN_MAX);
        checked(maxFd > 0, "descriptor limit");
        std::string home = "HOME=" + cwd;
        char term[] = "TERM=xterm-256color";
        char path[] = "PATH=/system/bin:/system/xbin";
        char lang[] = "LANG=C.UTF-8";
        char* env[] = {home.data(), term, path, lang, nullptr};
        char interactive[] = "-i";
        char* args[] = {const_cast<char*>(shell.c_str()), interactive, nullptr};
        struct sigaction action{};
        action.sa_handler = SIG_DFL;
        sigemptyset(&action.sa_mask);
        sigset_t mask;
        sigemptyset(&mask);
        pid_ = fork();
        checked(pid_ >= 0, "fork");
        if (pid_ == 0) {
            if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) childError(errorPipe[1]);
            if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0)
                childError(errorPipe[1]);
            closeOtherFds(errorPipe[1], maxFd);
            if (chdir(cwd.c_str()) < 0) childError(errorPipe[1]);
            for (int signal : {SIGHUP, SIGINT, SIGQUIT, SIGTERM, SIGPIPE, SIGCHLD, SIGTSTP, SIGTTIN, SIGTTOU, SIGWINCH})
                if (sigaction(signal, &action, nullptr) < 0) childError(errorPipe[1]);
            if (sigprocmask(SIG_SETMASK, &mask, nullptr) < 0) childError(errorPipe[1]);
            execve(shell.c_str(), args, env);
            childError(errorPipe[1]);
        }
        closeFd(slave); closeFd(errorPipe[1]);
        int error = 0;
        ssize_t count;
        do { count = ::read(errorPipe[0], &error, sizeof(error)); } while (count < 0 && errno == EINTR);
        checked(count >= 0, "exec status");
        if (count > 0) throw std::system_error(error, std::generic_category(), "child setup/exec");
        closeFd(errorPipe[0]);
    } catch (...) {
        closeFd(slave); closeFd(errorPipe[0]); closeFd(errorPipe[1]);
        stop();
        if (pid_ > 0) reap();
        closeFd(master_); closeFd(wake_[0]); closeFd(wake_[1]);
        throw;
    }
}

LocalPty::~LocalPty() {
    stop();
    if (pid_ > 0) reap();
    closeFd(master_); closeFd(wake_[0]); closeFd(wake_[1]);
}

bool LocalPty::ready(short events) {
    while (!stopped_) {
        pollfd descriptors[] = {{master_, events, 0}, {wake_[0], POLLIN, 0}};
        int result = poll(descriptors, 2, -1);
        if (result < 0 && errno == EINTR) continue;
        checked(result >= 0, "PTY poll");
        if (stopped_ || descriptors[1].revents) return false;
        if (descriptors[0].revents & (events | POLLHUP | POLLERR)) return true;
    }
    return false;
}
int LocalPty::readBytes(void* data, size_t size) {
    while (ready(POLLIN)) {
        ssize_t count = ::read(master_, data, size);
        if (count == 0 || (count < 0 && errno == EIO)) return -1; // Linux PTY hangup
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        checked(count >= 0, "PTY read");
        return static_cast<int>(count);
    }
    return -1;
}
void LocalPty::writeBytes(const void* data, size_t size) {
    auto* bytes = static_cast<const char*>(data);
    size_t offset = 0;
    while (offset < size && ready(POLLOUT)) {
        ssize_t count = ::write(master_, bytes + offset, size - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        checked(count > 0, "PTY write");
        offset += static_cast<size_t>(count);
    }
}
void LocalPty::resize(int columns, int rows, int width, int height) {
    if (stopped_) return;
    if (columns < 2 || columns > 65535 || rows < 2 || rows > 65535 || width < 0 || height < 0)
        throw std::invalid_argument("PTY size");
    winsize size{static_cast<unsigned short>(rows), static_cast<unsigned short>(columns),
                 static_cast<unsigned short>(width > 65535 ? 65535 : width),
                 static_cast<unsigned short>(height > 65535 ? 65535 : height)};
    checked(ioctl(master_, TIOCSWINSZ, &size) == 0, "TIOCSWINSZ");
}
int LocalPty::waitForExit() {
    std::lock_guard<std::mutex> waiter(waitMutex_);
    {
        std::lock_guard<std::mutex> child(childMutex_);
        if (exited_) return status_;
    }
    // Observe without reaping, so stop() cannot accidentally signal a reused PID.
    siginfo_t info{};
    int result;
    do { result = waitid(P_PID, pid_, &info, WEXITED | WNOWAIT); } while (result < 0 && errno == EINTR);
    std::lock_guard<std::mutex> child(childMutex_);
    status_ = result == 0 ? (info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status) : -1;
    exited_ = true;
    return status_;
}
void LocalPty::reap() {
    std::lock_guard<std::mutex> child(childMutex_);
    if (reaped_) return;
    int status;
    pid_t result;
    do { result = waitpid(pid_, &status, 0); } while (result < 0 && errno == EINTR);
    reaped_ = true;
}
void LocalPty::stop() {
    if (stopped_.exchange(true)) return;
    if (wake_[1] >= 0) { char wake = 1; (void)::write(wake_[1], &wake, 1); }
    std::lock_guard<std::mutex> child(childMutex_);
    if (pid_ > 0 && !reaped_) {
        // A shell can put jobs in other process groups. Shutdown is one bounded inventory,
        // never a persistent process monitor. The unreaped shell reserves the session ID.
        DIR* processes = opendir("/proc");
        if (processes) {
            while (auto* entry = readdir(processes)) {
                char* end;
                long candidate = strtol(entry->d_name, &end, 10);
                if (*end == '\0' && candidate > 0 && candidate != pid_ && getsid(static_cast<pid_t>(candidate)) == pid_)
                    kill(static_cast<pid_t>(candidate), SIGKILL);
            }
            closedir(processes);
        }
        pid_t foreground = tcgetpgrp(master_);
        if (foreground > 0 && foreground != pid_ && getsid(foreground) == pid_) kill(-foreground, SIGKILL);
        kill(-pid_, SIGKILL);
        kill(pid_, SIGKILL); // also handles setup failure before setsid
    }
}
