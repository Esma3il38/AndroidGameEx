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
                // Scan the buffer (Client-side scan)
                // We stop at bytesRead - 4 to avoid reading past the buffer end for a 4-byte int
                for (size_t i = 0; i <= bytesRead - 4; i += 4) {
                    // Reinterpret bytes as Integer
                    int val = *reinterpret_cast<int*>(&buffer[i]);

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

    int count = (searchResults.size() > limit) ? limit : searchResults.size();

    jlongArray resultArr = env->NewLongArray(count);
    env->SetLongArrayRegion(resultArr, 0, count, (jlong*)searchResults.data());

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