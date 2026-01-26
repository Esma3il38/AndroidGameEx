#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <sys/uio.h>
#include <unistd.h>
#include <android/log.h>

// Define the structure of a memory region
struct MemoryRegion {
    long startAddress;
    long endAddress;
    bool isReadable;
    bool isWritable;
    bool isExecutable;
};

// Global buffer to store found results (Address List)
// In a real app, this might be stored in a temporary file to save RAM.
std::vector<long> searchResults;

std::vector<MemoryRegion> getMemoryRegions(int pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream mapsFile(mapsPath);

    if (!mapsFile.is_open()) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Failed to open maps: %s", mapsPath.c_str());
        return regions;
    }

    std::string line;
    while (std::getline(mapsFile, line)) {
        MemoryRegion region;
        char permissions[5];
        char dev[10]; // Major:Minor
        long inode;
        char path[256] = {0}; // Optional path

        // Parse line: 7b3d8000-7b3da000 rw-p 00000000 00:00 0 ...
        // Using sscanf is fast and efficient for this standard format
        sscanf(line.c_str(), "%lx-%lx %4s %*s %s %ld %s",
               &region.startAddress, &region.endAddress, permissions, dev, &inode, path);

        region.isReadable = (permissions[0] == 'r');
        region.isWritable = (permissions[1] == 'w');
        region.isExecutable = (permissions[2] == 'x');

        // FILTER: We only want Readable and Writable memory (Data/Heap/Stack)
        // We explicitly skip video drivers (/dev/kgsl) and system fonts to avoid crashes
        if (region.isReadable && region.isWritable) {
             std::string pathStr(path);
             if (pathStr.find("/dev/") == std::string::npos &&
                 pathStr.find(".so") == std::string::npos &&
                 pathStr.find(".ttf") == std::string::npos) {
                regions.push_back(region);
             }
        }
    }
    return regions;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jint valueToFind) { // Currently supports Int (DWORD) only

    // 1. Clear previous results
    searchResults.clear();

    // 2. Get Valid Regions
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);

    // 3. Buffer for Chunk Reading (e.g., 4KB chunk)
    const size_t CHUNK_SIZE = 4096;
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    int matchCount = 0;

    // 4. Iterate over every region
    for (const auto& region : regions) {
        long currentAddr = region.startAddress;

        while (currentAddr < region.endAddress) {
            long remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : remaining;

            // Prepare iovec for process_vm_readv
            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead > 0) {
                if (bytesRead < static_cast<ssize_t>(sizeof(int))) {
                    currentAddr += readSize;
                    continue;
                }
                // Scan the buffer (Client-side scan)
                // We stop at bytesRead - 4 to avoid reading past the buffer end for a 4-byte int
                for (size_t i = 0; i + sizeof(int) <= static_cast<size_t>(bytesRead); i += 4) {
                    int val;
                    std::memcpy(&val, &buffer[i], sizeof(val));
                    if (val == valueToFind) {
                        // FOUND! Save the absolute address
                        searchResults.push_back(currentAddr + i);
                        matchCount++;

                        // Safety Limit: Prevent memory overflow if 1M+ results
                        if (matchCount >= 100000) break;
                    }
                }
            }
            currentAddr += readSize;
            if (matchCount >= 100000) break;
        }
        if (matchCount >= 100000) break;
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Search Complete. Found: %d", matchCount);
    return matchCount;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_techted89_gameex_NativeScanner_getResults(
        JNIEnv* env,
        jobject /* this */,
        jint limit) {

    int count = 0;
    if (limit > 0) {
        count = (searchResults.size() > static_cast<size_t>(limit))
            ? limit
            : static_cast<int>(searchResults.size());
    }
    jlongArray resultArr = env->NewLongArray(count);
    env->SetLongArrayRegion(resultArr, 0, count, (jlong*)searchResults.data());

    return resultArr;
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
