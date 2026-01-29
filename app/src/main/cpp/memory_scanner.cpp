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
#include <iostream>

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

enum SearchType {
    EXACT,
    RANGE,
    FUZZY,
    ENCRYPTED_XOR
};

struct SearchCondition {
    SearchType type = EXACT;
    int value1 = 0;
    int value2 = 0; // For range
    int xorKey = 0; // For encrypted
};

// Simple parser for "100~150", "100X8", "100"
SearchCondition parseSearchQuery(const std::string& query) {
    SearchCondition cond;

    // Check for Range (~)
    size_t tildePos = query.find('~');
    if (tildePos != std::string::npos) {
        cond.type = RANGE;
        try {
            cond.value1 = std::stoi(query.substr(0, tildePos));
            cond.value2 = std::stoi(query.substr(tildePos + 1));
        } catch (...) { cond.type = EXACT; }
        return cond;
    }

    // Check for XOR (X)
    size_t xPos = query.find('X');
    if (xPos != std::string::npos) {
        cond.type = ENCRYPTED_XOR;
        try {
            cond.value1 = std::stoi(query.substr(0, xPos));
            cond.xorKey = std::stoi(query.substr(xPos + 1));
        } catch (...) { cond.type = EXACT; }
        return cond;
    }

    // Default Exact
    cond.type = EXACT;
    try {
        cond.value1 = std::stoi(query);
    } catch (...) { cond.value1 = 0; }
    return cond;
}

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
        jint valueToFind) {
    // Legacy support: convert int to string and use the string-based search
    std::string query = std::to_string(valueToFind);
    jstring queryString = env->NewStringUTF(query.c_str());

    jint result = Java_com_techted89_gameex_NativeScanner_searchMemoryString(env, nullptr, pid, queryString);

    env->DeleteLocalRef(queryString);
    return result;
}

extern "C" /**
 * @brief Install a hook at the target address (Stub).
 *
 * In a real implementation, this would:
 * 1. Read original instructions.
 * 2. Write a jump/trampoline to the replacement address.
 * 3. Handle architecture specifics (ARM/ARM64).
 */
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_installHook(
        JNIEnv* env,
        jobject,
        jlong targetAddress,
        jlong replacementAddress) {

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "InstallHook: %" PRIx64 " -> %" PRIx64, (uint64_t)targetAddress, (uint64_t)replacementAddress);

    // Basic ARM64 Trampoline Generation (Absolute Jump)
    // LDR X16, #8
    // BR X16
    // .quad replacementAddress

    uint32_t trampoline[] = {
        0x58000050, // LDR X16, #8 (PC+8)
        0xD61F0200  // BR X16
    };

    // We need to write 8 bytes of code + 8 bytes of address = 16 bytes
    // Note: In reality, we must first backup the original instructions to support unhooking.
    // Assuming 'pid' context is available or using self-injection for test.
    // For external process, we use process_vm_writev.
    // BUT we don't have the PID here in arguments! The API needs PID.
    // Assuming self-hook for now or simplified context.

    // Ideally: process_vm_writev(pid, ...)

    return JNI_TRUE;
}

extern "C" /**
 * @brief Remove a previously installed hook (Stub).
 */
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_removeHook(
        JNIEnv* env,
        jobject,
        jlong targetAddress) {

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "RemoveHook: %" PRIx64, (uint64_t)targetAddress);
    // Stub: Simulate success
    return JNI_TRUE;
}

extern "C" /**
 * @brief Dump memory regions to files on disk.
 *
 * @param pid Target process ID.
 * @param from Start address (0 for all).
 * @param to End address (-1 for all).
 * @param path Directory path to save dumps.
 * @return True if successful.
 */
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_dumpMemory(
        JNIEnv* env,
        jobject,
        jint pid,
        jlong from,
        jlong to,
        jstring path) {

    const char* pathC = env->GetStringUTFChars(path, nullptr);
    std::string dumpDir(pathC);
    env->ReleaseStringUTFChars(path, pathC);

    // Ensure directory exists (mkdir handled in Java or assuming pre-existing for now)

    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 4096;
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    for (const auto& region : regions) {
        // Filter by range if specified
        if (from != 0 && region.endAddress < (uintptr_t)from) continue;
        if (to != -1 && region.startAddress > (uintptr_t)to) continue;

        uintptr_t start = region.startAddress;
        uintptr_t end = region.endAddress;

        // Clamp to requested range
        if (from != 0 && start < (uintptr_t)from) start = (uintptr_t)from;
        if (to != -1 && end > (uintptr_t)to) end = (uintptr_t)to;

        if (start >= end) continue;

        // Create filename: start-end.dump
        std::stringstream ss;
        ss << dumpDir << "/" << std::hex << start << "-" << end << ".dump";
        std::ofstream outFile(ss.str(), std::ios::binary);

        if (!outFile.is_open()) continue;

        uintptr_t current = start;
        while (current < end) {
            size_t readSize = std::min((size_t)(end - current), CHUNK_SIZE);
            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)current, readSize};

            ssize_t bytes = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);
            if (bytes > 0) {
                outFile.write((char*)buffer.data(), bytes);
            } else {
                // If read fails (e.g. guarded page), fill with zeros or skip
                // Ideally, skip to next page
            }
            current += readSize;
        }
        outFile.close();
    }

    return JNI_TRUE;
}

extern "C" /**
 * @brief Enable stealth mode to hide the scanner from the target process.
 *
 * In a real implementation, this would involve:
 * 1. Unlinking the shared library from the module list.
 * 2. Obfuscating /proc/self/maps entries.
 * 3. Detaching ptrace when idle.
 */
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_enableStealthMode(
        JNIEnv* env,
        jobject /* this */) {

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Stealth Mode: Activated");

    // Stub: Simulate masking process name
    // prctl(PR_SET_NAME, "com.android.system.service", 0, 0, 0);

    // Stub: Simulate unlinking
    // This is where advanced anti-anti-cheat logic would live.
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemoryString(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jstring queryString) {

    const char* queryCStr = env->GetStringUTFChars(queryString, nullptr);
    std::string query(queryCStr);
    env->ReleaseStringUTFChars(queryString, queryCStr);

    SearchCondition cond = parseSearchQuery(query);

    std::vector<jlong> localResults;
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);

    const size_t CHUNK_SIZE = 4096;
    std::vector<uint8_t> buffer(CHUNK_SIZE);
    int matchCount = 0;

    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;
        while (currentAddr < region.endAddress) {
            uintptr_t remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : (size_t)remaining;

            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead >= 4) {
                size_t limit = (size_t)bytesRead;
                for (size_t i = 0; i + 4 <= limit; i += 4) {
                    int val;
                    std::memcpy(&val, &buffer[i], sizeof(int));

                    bool match = false;
                    switch (cond.type) {
                        case EXACT:
                            match = (val == cond.value1);
                            break;
                        case RANGE:
                            match = (val >= cond.value1 && val <= cond.value2);
                            break;
                        case ENCRYPTED_XOR:
                            match = ((val ^ cond.xorKey) == cond.value1);
                            break;
                        default:
                            break;
                    }

                    if (match) {
                        localResults.push_back((jlong)(currentAddr + i));
                        matchCount++;
                        if (matchCount >= 100000) goto search_complete;
                    }
                }
            }
            currentAddr += readSize;
        }
    }

search_complete:
    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults = std::move(localResults);
    }
    return matchCount;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_startFuzzyScan(
        JNIEnv* env,
        jobject,
        jint pid,
        jstring dumpPath) {
    // Placeholder: In a real implementation, dump process memory to file
    // for subsequent comparison.
    // For now, we just clear results to simulate a "Wait for change" state.
    std::lock_guard<std::mutex> lock(searchResultsMutex);
    searchResults.clear();
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