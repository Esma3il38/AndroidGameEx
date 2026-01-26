#include <jni.h>
#include <sys/uio.h>
#include <vector>
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <string>
#include <iostream>

#define LOG_TAG "NativeScanner"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct MemoryRegion {
    long startAddress;
    long endAddress;
    long size;
    std::string permissions;
    std::string filename;
};

std::vector<MemoryRegion> parse_maps(int pid) {
    std::vector<MemoryRegion> regions;
    std::stringstream ss;
    ss << "/proc/" << pid << "/maps";
    std::string maps_path = ss.str();

    std::ifstream maps_file(maps_path);
    if (!maps_file.is_open()) {
        LOGE("Failed to open %s", maps_path.c_str());
        return regions;
    }

    std::string line;
    while (std::getline(maps_file, line)) {
        // Line format: 7ffc645e8000-7ffc645e9000 r-xp 00000000 00:00 0 [vdso]
        long start = 0, end = 0;
        char perms[5];
        char offset[20];
        char dev[10];
        int inode = 0;
        char filename_buf[256];
        filename_buf[0] = '\0'; // Initialize empty

        // Parsing using sscanf can be brittle, but standard for maps
        // We scan for hex-hex perms ...
        int items = sscanf(line.c_str(), "%lx-%lx %4s %s %s %d %255[^\n]",
                           &start, &end, perms, offset, dev, &inode, filename_buf);

        if (items < 2) continue; // Failed to parse basic address

        std::string permissions(perms);
        std::string filename(filename_buf);

        // Filter: Must be Readable and Writable (rw)
        // Note: Some game memory might be r-xp (code), but usually we want data (rw-p)
        // User instruction: "Filter IN regions that are readable and writable (rw)"
        if (permissions.find("rw") == std::string::npos) {
            continue;
        }

        // Filter OUT dangerous regions
        if (filename.find("/dev/kgsl") != std::string::npos) continue; // GPU memory
        if (filename.find(".ttf") != std::string::npos) continue; // Fonts
        if (filename.find("app_process") != std::string::npos) continue; // Process executable itself (often)

        // Construct region
        MemoryRegion region;
        region.startAddress = start;
        region.endAddress = end;
        region.size = end - start;
        region.permissions = permissions;
        region.filename = filename;

        regions.push_back(region);
    }
    return regions;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_techted89_gameex_NativeScanner_readMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jlong address,
        jint size) {

    std::vector<uint8_t> buffer(size);
    struct iovec local_iov = {buffer.data(), (size_t)size};
    struct iovec remote_iov = {(void*)address, (size_t)size};

    ssize_t bytes_read = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

    if (bytes_read == -1) {
        return env->NewByteArray(0);
    }

    jbyteArray result = env->NewByteArray(bytes_read);
    env->SetByteArrayRegion(result, 0, bytes_read, (jbyte*)buffer.data());
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_techted89_gameex_NativeScanner_getMemoryRegions(
        JNIEnv* env,
        jobject /* this */,
        jint pid) {

    std::vector<MemoryRegion> regions = parse_maps(pid);

    // Find the MemoryRegion class
    jclass regionClass = env->FindClass("com/techted89/gameex/MemoryRegion");
    if (regionClass == nullptr) {
        LOGE("Could not find MemoryRegion class");
        return nullptr;
    }

    // Get the constructor ID: (JJJLjava/lang/String;Ljava/lang/String;)V
    jmethodID constructor = env->GetMethodID(regionClass, "<init>", "(JJJLjava/lang/String;Ljava/lang/String;)V");
    if (constructor == nullptr) {
        LOGE("Could not find MemoryRegion constructor");
        return nullptr;
    }

    // Create the object array
    jobjectArray result = env->NewObjectArray(regions.size(), regionClass, nullptr);

    for (size_t i = 0; i < regions.size(); ++i) {
        jstring perms = env->NewStringUTF(regions[i].permissions.c_str());
        jstring fname = env->NewStringUTF(regions[i].filename.c_str());

        jobject regionObj = env->NewObject(regionClass, constructor,
                                           (jlong)regions[i].startAddress,
                                           (jlong)regions[i].endAddress,
                                           (jlong)regions[i].size,
                                           perms,
                                           fname);

        env->SetObjectArrayElement(result, i, regionObj);

        // Local refs cleanup
        env->DeleteLocalRef(perms);
        env->DeleteLocalRef(fname);
        env->DeleteLocalRef(regionObj);
    }

    return result;
}
