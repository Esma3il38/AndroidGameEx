#include <jni.h>
#include <sys/uio.h>
#include <vector>
#include <android/log.h>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_techted89_gameex_NativeScanner_readMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jlong address,
        jint size) {

    // 1. Prepare buffers
    std::vector<uint8_t> buffer(size);
    struct iovec local_iov = {buffer.data(), (size_t)size};
    struct iovec remote_iov = {(void*)address, (size_t)size};

    // 2. Perform the read syscall (requires Root/Ptrace scope)
    // process_vm_readv is significantly faster than ptrace PEEK_DATA
    ssize_t bytes_read = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

    if (bytes_read == -1) {
        // Return empty array on failure
        return env->NewByteArray(0);
    }

    // 3. Return data to Java
    jbyteArray result = env->NewByteArray(bytes_read);
    env->SetByteArrayRegion(result, 0, bytes_read, (jbyte*)buffer.data());
    return result;
}
