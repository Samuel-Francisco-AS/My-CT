#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/statvfs.h>
#include <unistd.h>

#include <chrono>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>

namespace {
using Clock = std::chrono::steady_clock;
const auto started_at = Clock::now();

struct CpuTimes {
    std::uint64_t total = 0;
    std::uint64_t idle = 0;
};

CpuTimes cpu_times() {
    std::ifstream file("/proc/stat");
    std::string label;
    if (!file || !(file >> label) || label != "cpu") {
        throw std::runtime_error("cannot read /proc/stat");
    }
    CpuTimes sample;
    std::uint64_t value = 0;
    int position = 0;
    std::string line;
    std::getline(file, line);
    std::istringstream fields(line);
    while (fields >> value) {
        sample.total += value;
        if (position == 3 || position == 4) sample.idle += value;
        ++position;
    }
    if (position < 4 || sample.total == 0) {
        throw std::runtime_error("incomplete /proc/stat");
    }
    return sample;
}

std::uint64_t meminfo_kib(const std::string& requested) {
    std::ifstream file("/proc/meminfo");
    if (!file) throw std::runtime_error("cannot read /proc/meminfo");
    std::string key, unit;
    std::uint64_t kib = 0;
    while (file >> key >> kib >> unit) {
        if (key == requested + ":" && unit == "kB") return kib;
    }
    throw std::runtime_error("missing memory metric");
}

std::string health_json() {
    const CpuTimes before = cpu_times();
    std::this_thread::sleep_for(std::chrono::milliseconds(120));
    const CpuTimes after = cpu_times();
    const auto elapsed = after.total - before.total;
    const auto idle = after.idle - before.idle;
    if (elapsed == 0 || idle > elapsed) throw std::runtime_error("invalid CPU sample");
    const double cpu_percent = 100.0 * static_cast<double>(elapsed - idle) / static_cast<double>(elapsed);

    const std::uint64_t ram_total = meminfo_kib("MemTotal") * 1024;
    const std::uint64_t ram_available = meminfo_kib("MemAvailable") * 1024;
    if (ram_available > ram_total) throw std::runtime_error("invalid RAM sample");

    struct statvfs disk {};
    if (statvfs("/", &disk) != 0) throw std::runtime_error("cannot inspect root disk");
    const auto disk_total = static_cast<std::uint64_t>(disk.f_blocks) * disk.f_frsize;
    const auto disk_available = static_cast<std::uint64_t>(disk.f_bavail) * disk.f_frsize;

    const auto uptime = std::chrono::duration_cast<std::chrono::seconds>(Clock::now() - started_at).count();
    std::ostringstream body;
    body.setf(std::ios::fixed);
    body.precision(1);
    body << "{\"status\":\"ok\",\"cpu_percent\":" << cpu_percent
         << ",\"ram_total_bytes\":" << ram_total
         << ",\"ram_available_bytes\":" << ram_available
         << ",\"disk_total_bytes\":" << disk_total
         << ",\"disk_available_bytes\":" << disk_available
         << ",\"uptime_seconds\":" << uptime << "}";
    return body.str();
}

void send_all(int fd, const std::string& content) {
    std::size_t offset = 0;
    while (offset < content.size()) {
        const ssize_t count = send(fd, content.data() + offset, content.size() - offset, MSG_NOSIGNAL);
        if (count <= 0) return;
        offset += static_cast<std::size_t>(count);
    }
}

void respond(int fd, int status, const char* reason, const std::string& body) {
    const std::string header = "HTTP/1.1 " + std::to_string(status) + " " + reason
        + "\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store"
          "\r\nConnection: close\r\nContent-Length: " + std::to_string(body.size()) + "\r\n\r\n";
    send_all(fd, header + body);
}

void handle_client(int fd) {
    timeval timeout{};
    timeout.tv_sec = 3;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    char buffer[4096];
    const ssize_t count = recv(fd, buffer, sizeof(buffer), 0);
    if (count <= 0) return;
    const std::string request(buffer, static_cast<std::size_t>(count));
    const auto first_line_end = request.find("\r\n");
    if (first_line_end == std::string::npos) {
        respond(fd, 400, "Bad Request", "{\"error\":\"bad request\"}");
        return;
    }
    std::istringstream line(request.substr(0, first_line_end));
    std::string method, path, version, extra;
    if (!(line >> method >> path >> version) || (line >> extra) ||
        (version != "HTTP/1.1" && version != "HTTP/1.0")) {
        respond(fd, 400, "Bad Request", "{\"error\":\"bad request\"}");
    } else if (method != "GET") {
        respond(fd, 405, "Method Not Allowed", "{\"error\":\"read only\"}");
    } else if (path != "/v1/health") {
        respond(fd, 404, "Not Found", "{\"error\":\"not found\"}");
    } else {
        try {
            respond(fd, 200, "OK", health_json());
        } catch (const std::exception&) {
            respond(fd, 503, "Service Unavailable", "{\"error\":\"metric unavailable\"}");
        }
    }
}

int parse_port(const char* input) {
    if (input == nullptr || *input == '\0') throw std::runtime_error("invalid port");
    char* end = nullptr;
    const long port = std::strtol(input, &end, 10);
    if (*end != '\0' || port < 1024 || port > 65535) {
        throw std::runtime_error("port must be between 1024 and 65535");
    }
    return static_cast<int>(port);
}

void serve(int port) {
    const int server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0) throw std::runtime_error("socket failed");
    const int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<std::uint16_t>(port));
    // Intentionally loopback-only. Remote Android access must use an authenticated tunnel.
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0 ||
        listen(server, 8) != 0) {
        const auto message = std::string("listen failed: ") + std::strerror(errno);
        close(server);
        throw std::runtime_error(message);
    }
    std::cout << "My-CT Core: http://127.0.0.1:" << port << "/v1/health" << std::endl;
    while (true) {
        const int client = accept(server, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            throw std::runtime_error("accept failed");
        }
        handle_client(client);
        close(client);
    }
}
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--once") {
            std::cout << health_json() << '\n';
        } else if (argc == 1) {
            serve(8765);
        } else if (argc == 3 && std::string(argv[1]) == "--port") {
            serve(parse_port(argv[2]));
        } else {
            std::cerr << "Usage: myct-core [--once | --port 8765]\n";
            return 2;
        }
    } catch (const std::exception& error) {
        std::cerr << "My-CT Core: " << error.what() << '\n';
        return 1;
    }
    return 0;
}
