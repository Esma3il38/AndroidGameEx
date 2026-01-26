#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <sys/uio.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <algorithm>
#include <cinttypes>

// Define the structure of a memory region
struct MemoryRegion {
    uintptr_t startAddress;
    uintptr_t endAddress;
    bool isReadable;
    bool isWritable;
    bool isExecutable;
    bool isValid;
    std::string filename;
};

// Global buffer to store found results (Address List)
// In a real app, this might be stored in a temporary file to save RAM.
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

/**
 * @brief Checks if a path should be filtered out.
 *
 * @param path The path string associated with a memory region.
 * @return true if the path contains filtered patterns, false otherwise.
 */
static bool shouldFilterPath(const std::string& path) {
    if (path.find("/dev/") != std::string::npos) return true;
    if (path.find(".so") != std::string::npos) return true;
    if (path.find(".ttf") != std::string::npos) return true;
    if (path.find("app_process") != std::string::npos) return true;
    return false;
}

/**
 * @brief Parses a single line from the memory map file.
 *
 * @param line The line string to parse.
 * @return A MemoryRegion struct with parsing results and validity indicator.
 */
static MemoryRegion parseMemoryMapLine(const std::string& line) {
    MemoryRegion region;
    // Default initialization
    region.startAddress = 0;
    region.endAddress = 0;
    region.isReadable = false;
    region.isWritable = false;
    region.isExecutable = false;
    region.isValid = false;
    region.filename = "";

    char permissions[5] = {0};
    char dev[10] = {0}; // Major:Minor
    long inode = 0;
    char path[4096] = {0}; // Optional path, large buffer to avoid truncation
    int pos = 0;

    // Parse line: 7b3d8000-7b3da000 rw-p 00000000 00:00 0 ...
    // We use %n to see where the fixed fields end, then manually grab the path.
    // Format: start-end perms offset dev inode
    int parsed = sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s %*s %9s %ld%n",
           &region.startAddress, &region.endAddress, permissions, dev, &inode, &pos);

    if (parsed < 5) {
        return region;
    }

    // Extract path if present
    if (pos > 0 && (size_t)pos < line.length()) {
        const char* p = line.c_str() + pos;
        // Skip leading whitespace
        while (*p == ' ' || *p == '\t') {
            p++;
        }
        // Copy to path buffer
        strncpy(path, p, sizeof(path) - 1);
        path[sizeof(path) - 1] = '\0'; // Ensure null-termination

        // Remove trailing newline if present (getline usually handles this, but just in case of oddities)
        size_t len = strlen(path);
        if (len > 0 && path[len-1] == '\n') {
            path[len-1] = '\0';
        }
    } else {
        path[0] = '\0';
    }

    region.isReadable = (permissions[0] == 'r');
    region.isWritable = (permissions[1] == 'w');
    region.isExecutable = (permissions[2] == 'x');
    region.filename = std::string(path);
    region.isValid = true;

    return region;
}

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
        MemoryRegion region = parseMemoryMapLine(line);

        if (!region.isValid) {
            continue;
        }

        // FILTER: We only want Readable and Writable memory (Data/Heap/Stack)
        // We explicitly skip video drivers (/dev/kgsl) and system fonts to avoid crashes
        if (region.isReadable && region.isWritable) {
             if (!shouldFilterPath(region.filename)) {
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
    // Use a local vector to accumulate results to avoid locking during the scan
    std::vector<jlong> localResults;

    // 2. Get Valid Regions
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);

    // 3. Buffer for Chunk Reading (e.g., 4KB chunk)
    const size_t CHUNK_SIZE = 4096;
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    int matchCount = 0;
    bool searchComplete = false;

    // 4. Iterate over every region
    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;

        while (currentAddr < region.endAddress) {
            uintptr_t remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : (size_t)remaining;

            // Prepare iovec for process_vm_readv
            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead <= 0) {
                // Error reading memory or empty read.
                // Advance by readSize to skip this chunk and try next,
                // or just break if we assume the rest of the region is unreadable.
                // For robustness, we skip this chunk.
                currentAddr += readSize;
                continue;
            }

            // Use actual bytesRead for the limit
            if (bytesRead >= 4) {
                // Scan the buffer (Client-side scan)
                // We stop at i + 4 <= bytesRead to avoid reading past the buffer end for a 4-byte int
                size_t limit = (size_t)bytesRead;

                // Optimized for 4-byte aligned searches.
                // Note: This loop increments by 4, skipping unaligned occurrences.
                for (size_t i = 0; i + 4 <= limit; i += 4) {
                    // Safe unaligned access using memcpy
                    int val;
                    std::memcpy(&val, &buffer[i], sizeof(int));

                    if (val == valueToFind) {
                        // FOUND! Save the absolute address
                        localResults.push_back((jlong)(currentAddr + i));
                        matchCount++;

                        // Safety Limit: Prevent memory overflow if 1M+ results
                        if (matchCount >= 100000) {
                            searchComplete = true;
                            break;
                        }
                    }
                }
            }
            if (searchComplete) break;

            // Advance by the actual amount read to ensure we don't skip data
            // if we got a partial read, or the full chunk if successful.
            currentAddr += (size_t)bytesRead;
        }
        if (searchComplete) break;
    }

    // Update global searchResults safely
    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults = std::move(localResults);
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Search Complete. Found: %d", matchCount);
    return matchCount;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_techted89_gameex_NativeScanner_getResults(
        JNIEnv* env,
        jobject /* this */,
        jint limit) {
    std::lock_guard<std::mutex> lock(searchResultsMutex);

    size_t safeLimit = (limit < 0) ? 0 : (size_t)limit;
    size_t count = std::min(searchResults.size(), safeLimit);

    jlongArray resultArr = env->NewLongArray(count);
    if (resultArr == nullptr) {
        return env->NewLongArray(0); // Return empty array on error instead of nullptr
    }

    if (count > 0) {
        env->SetLongArrayRegion(resultArr, 0, count, searchResults.data());
    }

    return resultArr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_techted89_gameex_NativeScanner_readMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jlong address,
        jint size) {
    // Validate size: must be positive and not too huge (e.g., limit to 1MB)
    // This prevents massive allocation attempts and invalid inputs.
    if (size <= 0 || size > 1024 * 1024) {
        return env->NewByteArray(0);
    }

    std::vector<uint8_t> buffer(size);
    struct iovec local_iov = {buffer.data(), (size_t)size};
    struct iovec remote_iov = {(void*)(uintptr_t)address, (size_t)size};

    ssize_t bytes_read = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

    if (bytes_read == -1) {
        return env->NewByteArray(0);
    }

    jbyteArray result = env->NewByteArray(bytes_read);
    if (result == nullptr) {
        // Return empty array instead of nullptr on OOM to be consistent with other error paths
        return env->NewByteArray(0);
    }

    env->SetByteArrayRegion(result, 0, bytes_read, (jbyte*)buffer.data());
    return result;
}
