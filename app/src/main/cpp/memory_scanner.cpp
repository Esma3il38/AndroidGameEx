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
};

// Global buffer to store found results (Address List)
// In a real app, this might be stored in a temporary file to save RAM.
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

/**
 * @brief Collects readable and writable memory regions for a given process.
 *
 * Parses the target process's memory map and returns a list of MemoryRegion entries
 * that are marked readable and writable and whose path does not indicate device nodes,
 * shared libraries (.so), or font files (.ttf).
 *
 * @param pid Process ID whose /proc/[pid]/maps will be examined.
 * @return std::vector<MemoryRegion> Vector of filtered MemoryRegion objects; empty if the maps file cannot be opened or no regions match the criteria.
 */
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
        int pos = 0;

        // Parse line: 7b3d8000-7b3da000 rw-p 00000000 00:00 0 ...
        // We use %n to see where the fixed fields end, then manually grab the path.
        // Format: start-end perms offset dev inode
        int parsed = sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s %*s %9s %ld%n",
               &region.startAddress, &region.endAddress, permissions, dev, &inode, &pos);

        if (parsed < 5) {
            continue;
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

extern "C" /**
 * @brief Scans a target process's readable-and-writable memory regions for a 32-bit integer value.
 *
 * Populates the module-global searchResults with absolute addresses of every 4-byte-aligned match
 * and stops when either the entire addressable set of filtered regions has been scanned or a safety
 * limit of 100000 matches is reached.
 *
 * @param env JNI environment (unused in behavior description).
 * @param[in] pid Target process ID whose memory will be scanned.
 * @param[in] valueToFind 32-bit integer value to search for.
 * @return jint The number of matches recorded in searchResults (capped at 100000).
 */
JNIEXPORT jint JNICALL
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

            if (bytesRead >= 4) {
                // Scan the buffer (Client-side scan)
                // We stop at i + 4 <= bytesRead to avoid reading past the buffer end for a 4-byte int
                size_t limit = (size_t)bytesRead;
                for (size_t i = 0; i + 4 <= limit; i += 4) {
                    // Safe unaligned access using memcpy
                    int val;
                    std::memcpy(&val, &buffer[i], sizeof(int));

                    if (val == valueToFind) {
                        // FOUND! Save the absolute address
                        localResults.push_back((jlong)(currentAddr + i));
                        matchCount++;

                        // Safety Limit: Prevent memory overflow if 1M+ results
                        if (matchCount >= 100000) goto search_complete;
                    }
                }
            }
            currentAddr += readSize;
        }
    }

search_complete:
    // Update global searchResults safely
    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults = std::move(localResults);
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Search Complete. Found: %d", matchCount);
    return matchCount;
}

extern "C" /**
 * @brief Create a Java long array populated with found memory addresses.
 *
 * Constructs and returns a Java long array containing up to `limit` addresses
 * taken from the native `searchResults` buffer.
 *
 * @param limit Maximum number of addresses to return; if `limit` is greater
 *              than the number of stored results, all available results are returned.
 * @return jlongArray Java long array whose length is min(limit, number of stored results)
 */
JNIEXPORT jlongArray JNICALL
Java_com_techted89_gameex_NativeScanner_getResults(
        JNIEnv* env,
        jobject /* this */,
        jint limit) {

    std::lock_guard<std::mutex> lock(searchResultsMutex);

    size_t safeLimit = (limit < 0) ? 0 : (size_t)limit;
    size_t count = std::min(searchResults.size(), safeLimit);

    jlongArray resultArr = env->NewLongArray(count);
    if (resultArr == nullptr) {
        return nullptr; // OOM or error
    }

    if (count > 0) {
        env->SetLongArrayRegion(resultArr, 0, count, searchResults.data());
    }

    return resultArr;
}

extern "C" /**
 * @brief Read a block of memory from a target process and return it as a Java byte array.
 *
 * @param pid Target process ID to read memory from.
 * @param address Start address in the target process to read.
 * @param size Number of bytes to read starting at `address`.
 * @return jbyteArray Java byte array containing the bytes actually read from the target process; an empty array if the read failed.
 */
JNIEXPORT jbyteArray JNICALL
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
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, bytes_read, (jbyte*)buffer.data());
    return result;
}

extern "C" /**
 * @brief Filter the current search results (Next Scan).
 *
 * Iterates through the existing results in `searchResults`, reads the memory at each address,
 * and keeps only those that equal `valueToFind`.
 *
 * @param pid Target process ID.
 * @param valueToFind New value to filter by.
 * @return Number of matches remaining.
 */
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jint valueToFind) {

    std::lock_guard<std::mutex> lock(searchResultsMutex);

    // Use the erase-remove idiom to filter in-place
    // We must read memory for each address.
    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
        int val = 0;
        struct iovec local_iov = {&val, sizeof(int)};
        struct iovec remote_iov = {(void*)(uintptr_t)addr, sizeof(int)};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

        // If read fails or value doesn't match, we REMOVE it (return true)
        if (bytesRead != sizeof(int)) {
            return true;
        }
        return val != valueToFind;
    });

    searchResults.erase(it, searchResults.end());

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Filter Complete. Remaining: %zu", searchResults.size());
    return (jint)searchResults.size();
}

extern "C" /**
 * @brief Get loaded modules (.so files) for the process.
 *
 * @param pid Target process ID.
 * @return String array of module paths.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_techted89_gameex_NativeScanner_getLoadedModules(
        JNIEnv* env,
        jobject /* this */,
        jint pid) {

    std::vector<std::string> modules;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream mapsFile(mapsPath);

    if (mapsFile.is_open()) {
        std::string line;
        while (std::getline(mapsFile, line)) {
            // Simple check for .so in the line
            if (line.find(".so") != std::string::npos) {
                // Extract path (simplistic approach: finding last space)
                // Maps format: address perms offset dev inode PATH
                // PATH starts after the last space/tab

                // Find start of path (heuristic: last token)
                // Or better, use our previous parsing logic but looking for non-empty path
                size_t lastSpace = line.find_last_of(" \t");
                if (lastSpace != std::string::npos && lastSpace + 1 < line.length()) {
                     std::string path = line.substr(lastSpace + 1);
                     // Avoid duplicates if multiple segments map the same .so
                     if (std::find(modules.begin(), modules.end(), path) == modules.end()) {
                         modules.push_back(path);
                     }
                }
            }
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(modules.size(), stringClass, nullptr);

    for (size_t i = 0; i < modules.size(); ++i) {
        jstring s = env->NewStringUTF(modules[i].c_str());
        env->SetObjectArrayElement(result, i, s);
        env->DeleteLocalRef(s);
    }

    return result;
}