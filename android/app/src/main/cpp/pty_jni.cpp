#include "local_pty.h"
#include <jni.h>
#include <array>
#include <algorithm>
#include <stdexcept>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<LocalPty>> sessions;
jlong nextId = 1;
std::shared_ptr<LocalPty> session(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto found = sessions.find(id);
    if (found == sessions.end()) throw std::runtime_error("Closed PTY");
    return found->second; // Keeps FDs alive through concurrent stop/release and blocked IO.
}
void failure(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_myct_NativePty_open(JNIEnv* env, jobject, jstring directory) {
    const char* value = env->GetStringUTFChars(directory, nullptr);
    if (!value) return 0;
    std::string cwd(value);
    env->ReleaseStringUTFChars(directory, value);
    try {
        auto pty = std::make_shared<LocalPty>("/system/bin/sh", cwd);
        std::lock_guard<std::mutex> lock(registryMutex);
        jlong id = nextId++;
        sessions.emplace(id, std::move(pty));
        return id;
    } catch (const std::exception& error) { failure(env, error); return 0; }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_myct_NativePty_read(JNIEnv* env, jobject, jlong id, jbyteArray output) {
    try {
        std::array<jbyte, 8192> bytes{};
        int length = env->GetArrayLength(output);
        if (length < 1) throw std::invalid_argument("Empty PTY read");
        int count = session(id)->readBytes(bytes.data(), std::min(length, 8192));
        if (count > 0) env->SetByteArrayRegion(output, 0, count, bytes.data());
        return count;
    } catch (const std::exception& error) { failure(env, error); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_myct_NativePty_write(JNIEnv* env, jobject, jlong id, jbyteArray input, jint count) {
    try {
        if (count < 0 || count > env->GetArrayLength(input)) throw std::invalid_argument("PTY write size");
        auto pty = session(id);
        std::array<jbyte, 8192> bytes{};
        for (int offset = 0; offset < count;) {
            int size = std::min(count - offset, 8192);
            env->GetByteArrayRegion(input, offset, size, bytes.data());
            if (env->ExceptionCheck()) return;
            pty->writeBytes(bytes.data(), size);
            offset += size;
        }
    } catch (const std::exception& error) { failure(env, error); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_myct_NativePty_resize(JNIEnv* env, jobject, jlong id, jint columns, jint rows, jint width, jint height) {
    try { session(id)->resize(columns, rows, width, height); }
    catch (const std::exception& error) { failure(env, error); }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_myct_NativePty_waitForExit(JNIEnv* env, jobject, jlong id) {
    try { return session(id)->waitForExit(); }
    catch (const std::exception& error) { failure(env, error); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_myct_NativePty_stop(JNIEnv* env, jobject, jlong id) {
    try { session(id)->stop(); }
    catch (const std::exception& error) { failure(env, error); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_myct_NativePty_release(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<LocalPty> pty;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto found = sessions.find(id);
        if (found == sessions.end()) return;
        pty = std::move(found->second);
        sessions.erase(found);
    }
    pty->stop(); // Destruction/reap never under registry lock.
}
