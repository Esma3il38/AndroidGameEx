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
#include <set>
#include <map>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <errno.h>
#include <unistd.h>

// Forward Declaration for JNI compatibility to resolve circular dependency
extern "C" JNIEXPORT jint JNICALL Java_com_techted89_gameex_NativeScanner_searchMemoryString(JNIEnv* env, jobject thiz, jint pid, jstring queryString);

// Define the structure of a memory region
struct MemoryRegion {
    uintptr_t startAddress;
    uintptr_t endAddress;
    bool isReadable;
    bool isWritable;
    bool isExecutable;
};

// Global buffer to store found results (Address List)
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

// Global map to store original bytes for unhooking
// Key: Target Address, Value: Original Bytes
std::map<jlong, std::vector<uint8_t>> originalBytesMap;
std::mutex originalBytesMutex;

// Optimized storage for Fuzzy Scan baseline (Memory Region Snapshots)
// This avoids the massive overhead of storing (address, value) pairs.
struct SnapshotRegion {
    uintptr_t startAddress;
    std::vector<uint8_t> data;
};

std::vector<SnapshotRegion> fuzzySnapshots;
std::mutex fuzzyMutex;

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

std::string readProcMaps(int pid) {
    if (pid <= 0) return "";

    int pipefd[2];
    if (pipe(pipefd) == -1) {
        return "";
    }

    pid_t child = fork();
    if (child == -1) {
        close(pipefd[0]);
        close(pipefd[1]);
        return "";
    }

    if (child == 0) {
        // Child
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);

        std::string path = "/proc/" + std::to_string(pid) + "/maps";
        std::string cmd = "cat " + path;

        // Execute su -c "cat /proc/pid/maps"
        // Using execlp ensures no shell expansion vulnerability beyond what su itself does
        execlp("su", "su", "-c", cmd.c_str(), (char *)NULL);
        _exit(127);
    }

    // Parent
    close(pipefd[1]);

    std::string output;
    char buffer[4096];
    ssize_t count;
    while ((count = read(pipefd[0], buffer, sizeof(buffer))) > 0) {
        output.append(buffer, count);
    }
    close(pipefd[0]);
    waitpid(child, nullptr, 0);

    return output;
}

/**
 * @brief Collects readable and writable memory regions for a given process.
 */
std::vector<MemoryRegion> getMemoryRegions(int pid) {
    std::vector<MemoryRegion> regions;
    std::string mapsData = readProcMaps(pid);
    if (mapsData.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Failed to read maps for PID: %d", pid);
        return regions;
    }

    std::stringstream ss(mapsData);
    std::string line;
    while (std::getline(ss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back(); // Handle CRLF if any

        MemoryRegion region;
        char permissions[5];
        char dev[16];
        long inode;
        char path[1024] = {0};
        int pos = 0;

        // sscanf is dangerous for strings, we parse fixed fields first
        int parsed = sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s %*s %15s %ld%n",
               &region.startAddress, &region.endAddress, permissions, dev, &inode, &pos);

        if (parsed < 5) continue;

        // Manually extract path to avoid buffer overflow with %s
        if (pos > 0 && (size_t)pos < line.length()) {
            const char* p = line.c_str() + pos;
            while (*p == ' ' || *p == '\t') p++;

            // Safe copy
            size_t i = 0;
            while (*p && i < sizeof(path) - 1) {
                path[i++] = *p++;
            }
            path[i] = '\0';
        }

        region.isReadable = (permissions[0] == 'r');
        region.isWritable = (permissions[1] == 'w');
        region.isExecutable = (permissions[2] == 'x');

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

// Helper to safely write memory using ptrace (handles alignment and read-modify-write)
bool ptraceWrite(int pid, uintptr_t addr, const void* data, size_t size) {
    const uint8_t* src = (const uint8_t*)data;
    uintptr_t currentAddr = addr;
    size_t bytesWritten = 0;

    while (bytesWritten < size) {
        // Align address to word boundary (8 bytes for 64-bit)
        uintptr_t alignedAddr = currentAddr & ~(sizeof(long) - 1);
        size_t offset = currentAddr - alignedAddr;

        errno = 0;
        long word = ptrace(PTRACE_PEEKTEXT, pid, alignedAddr, nullptr);
        if (errno != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "ptrace PEEK failed at %" PRIxPTR ": %s", alignedAddr, strerror(errno));
            return false;
        }

        uint8_t* wordBytes = (uint8_t*)&word;
        size_t chunk = std::min(sizeof(long) - offset, size - bytesWritten);

        // Modify the bytes in the word
        for (size_t i = 0; i < chunk; i++) {
            wordBytes[offset + i] = src[bytesWritten + i];
        }

        if (ptrace(PTRACE_POKETEXT, pid, alignedAddr, (void*)word) == -1) {
            __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "ptrace POKE failed at %" PRIxPTR ": %s", alignedAddr, strerror(errno));
            return false;
        }

        currentAddr += chunk;
        bytesWritten += chunk;
    }
    return true;
}

// Helper to safely read memory using ptrace (for backup)
bool ptraceRead(int pid, uintptr_t addr, void* dest, size_t size) {
    uint8_t* out = (uint8_t*)dest;
    uintptr_t currentAddr = addr;
    size_t bytesRead = 0;

    while (bytesRead < size) {
        uintptr_t alignedAddr = currentAddr & ~(sizeof(long) - 1);
        size_t offset = currentAddr - alignedAddr;

        errno = 0;
        long word = ptrace(PTRACE_PEEKTEXT, pid, alignedAddr, nullptr);
        if (errno != 0) {
            return false;
        }

        uint8_t* wordBytes = (uint8_t*)&word;
        size_t chunk = std::min(sizeof(long) - offset, size - bytesRead);

        for (size_t i = 0; i < chunk; i++) {
            out[bytesRead + i] = wordBytes[offset + i];
        }

        currentAddr += chunk;
        bytesRead += chunk;
    }
    return true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemoryString(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jstring queryString) {

    if (queryString == nullptr) {
        return 0;
    }

    const char* queryCStr = env->GetStringUTFChars(queryString, nullptr);
    if (queryCStr == nullptr) {
        return 0;
    }
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
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemory(
        JNIEnv* env,
        jobject thiz,
        jint pid,
        jint valueToFind) {
    std::string query = std::to_string(valueToFind);
    jstring queryString = env->NewStringUTF(query.c_str());
    if (queryString == nullptr) {
        return 0;
    }
    jint result = Java_com_techted89_gameex_NativeScanner_searchMemoryString(env, thiz, pid, queryString);
    env->DeleteLocalRef(queryString);
    return result;
}

extern "C" /**
 * @brief Install a hook at the target address.
 */
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_installHook(
        JNIEnv* env,
        jobject,
        jint pid,
        jlong targetAddress,
        jlong replacementAddress) {

#if defined(__aarch64__)
    if (targetAddress == 0 || replacementAddress == 0) return JNI_FALSE;

    // ARM64 Absolute Jump Trampoline (16 bytes)
    // LDR X16, #8 (PC+8) -> Load the address at PC+8 into X16
    // BR X16             -> Branch to X16
    // [64-bit Address]
    uint32_t trampolineCode[] = {
        0x58000050, // LDR X16, #8
        0xD61F0200  // BR X16
    };

    std::vector<uint8_t> trampoline(16);
    memcpy(trampoline.data(), trampolineCode, 8);
    memcpy(trampoline.data() + 8, &replacementAddress, 8);

    // Check for Self-Patching
    if (pid == getpid()) {
        long pageSize = sysconf(_SC_PAGESIZE);
        void* pageStart = (void*)(targetAddress & ~(pageSize - 1));
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
             __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "mprotect failed: %s", strerror(errno));
             return JNI_FALSE;
        }

        // Backup original bytes for self-patching
        std::vector<uint8_t> backup(16);
        memcpy(backup.data(), (void*)targetAddress, 16);
        {
            std::lock_guard<std::mutex> lock(originalBytesMutex);
            originalBytesMap[targetAddress] = backup;
        }

        std::memcpy((void*)targetAddress, trampoline.data(), trampoline.size());
        __builtin___clear_cache((char*)targetAddress, (char*)targetAddress + trampoline.size());
        return JNI_TRUE;
    }

    // External Process: Attach to process to freeze it and enable ptrace access
    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Failed to attach: %s", strerror(errno));
        return JNI_FALSE;
    }
    waitpid(pid, nullptr, 0);

    // 2. Backup original bytes
    std::vector<uint8_t> backup(16);
    if (!ptraceRead(pid, (uintptr_t)targetAddress, backup.data(), 16)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return JNI_FALSE;
    }

    // Save backup
    {
        std::lock_guard<std::mutex> lock(originalBytesMutex);
        originalBytesMap[targetAddress] = backup;
    }

    // 3. Write Trampoline
    bool success = ptraceWrite(pid, (uintptr_t)targetAddress, trampoline.data(), 16);

    // 4. Detach
    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    return success ? JNI_TRUE : JNI_FALSE;
#else
    __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Hook installation only supported on ARM64");
    return JNI_FALSE;
#endif
}

extern "C" /**
 * @brief Remove a previously installed hook.
 */
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_removeHook(
        JNIEnv* env,
        jobject,
        jint pid,
        jlong targetAddress) {

    std::vector<uint8_t> backup;
    {
        std::lock_guard<std::mutex> lock(originalBytesMutex);
        auto it = originalBytesMap.find(targetAddress);
        if (it == originalBytesMap.end()) {
            return JNI_FALSE; // No backup found
        }
        backup = it->second;
    }

    // Check for Self-Patching
    if (pid == getpid()) {
        long pageSize = sysconf(_SC_PAGESIZE);
        void* pageStart = (void*)(targetAddress & ~(pageSize - 1));
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
             __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "mprotect failed: %s", strerror(errno));
             return JNI_FALSE;
        }
        std::memcpy((void*)targetAddress, backup.data(), backup.size());
        __builtin___clear_cache((char*)targetAddress, (char*)targetAddress + backup.size());

        {
            std::lock_guard<std::mutex> lock(originalBytesMutex);
            originalBytesMap.erase(targetAddress);
        }
        return JNI_TRUE;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) {
        return JNI_FALSE;
    }
    waitpid(pid, nullptr, 0);

    bool success = ptraceWrite(pid, (uintptr_t)targetAddress, backup.data(), backup.size());

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    if (success) {
        std::lock_guard<std::mutex> lock(originalBytesMutex);
        originalBytesMap.erase(targetAddress);
    }

    return success ? JNI_TRUE : JNI_FALSE;
}

bool dumpMemoryInternal(int pid, long from, long to, const std::string& dumpDir) {
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 4096;
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    for (const auto& region : regions) {
        if (from != 0 && region.endAddress < (uintptr_t)from) continue;
        if (to != -1 && region.startAddress > (uintptr_t)to) continue;

        uintptr_t start = region.startAddress;
        uintptr_t end = region.endAddress;

        if (from != 0 && start < (uintptr_t)from) start = (uintptr_t)from;
        if (to != -1 && end > (uintptr_t)to) end = (uintptr_t)to;

        if (start >= end) continue;

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
            }
            current += readSize;
        }
        outFile.close();
    }
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_dumpMemory(
        JNIEnv* env,
        jobject,
        jint pid,
        jlong from,
        jlong to,
        jstring path) {

    if (path == nullptr) return JNI_FALSE;

    const char* pathC = env->GetStringUTFChars(path, nullptr);
    if (pathC == nullptr) return JNI_FALSE;
    std::string dumpDir(pathC);
    env->ReleaseStringUTFChars(path, pathC);

    return dumpMemoryInternal(pid, from, to, dumpDir) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_enableStealthMode(
        JNIEnv* env,
        jobject /* this */) {
    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Stealth Mode: Activated");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_startFuzzyScan(
        JNIEnv* env,
        jobject,
        jint pid,
        jstring dumpPath) {

    // Process dumpPath if provided
    bool hasDump = (dumpPath != nullptr);
    std::string path;
    if (hasDump) {
        const char* pathC = env->GetStringUTFChars(dumpPath, nullptr);
        if (pathC) {
            path = pathC;
            env->ReleaseStringUTFChars(dumpPath, pathC);
        } else {
            hasDump = false;
        }
    }

    // Populate local snapshot first (avoid holding global lock during I/O)
    std::vector<SnapshotRegion> newSnapshots;
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 64 * 1024; // Read 64KB chunks to minimize syscalls
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;
        while (currentAddr < region.endAddress) {
            uintptr_t remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : (size_t)remaining;

            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead > 0) {
                SnapshotRegion snapshot;
                snapshot.startAddress = currentAddr;
                snapshot.data.assign(buffer.begin(), buffer.begin() + bytesRead);
                newSnapshots.push_back(std::move(snapshot));
            }
            currentAddr += readSize;
        }
    }

    // Sort newSnapshots by startAddress to guarantee binary search works later
    std::sort(newSnapshots.begin(), newSnapshots.end(),
              [](const SnapshotRegion& a, const SnapshotRegion& b) {
                  return a.startAddress < b.startAddress;
              });

    // Update global state under lock
    {
        std::lock(searchResultsMutex, fuzzyMutex);
        std::lock_guard<std::mutex> lk1(searchResultsMutex, std::adopt_lock);
        std::lock_guard<std::mutex> lk2(fuzzyMutex, std::adopt_lock);

        searchResults.clear();
        fuzzySnapshots = std::move(newSnapshots);

        __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Scan Baseline: %zu regions captured", fuzzySnapshots.size());
    }

    // Perform dump for fuzzy scan baseline (Legacy/Debug support) if requested
    if (hasDump) {
        dumpMemoryInternal(pid, 0, -1, path);
        __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Scan Baseline Dumped to: %s", path.c_str());
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterFuzzy(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jint mode) {

    // Mode: 0=CHANGED, 1=UNCHANGED, 2=INCREASED, 3=DECREASED
    if (mode < 0 || mode > 3) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Invalid filter mode: %d", mode);
        return 0;
    }

    std::lock(searchResultsMutex, fuzzyMutex);
    std::lock_guard<std::mutex> lk1(searchResultsMutex, std::adopt_lock);
    std::lock_guard<std::mutex> lk2(fuzzyMutex, std::adopt_lock);

    if (fuzzySnapshots.empty()) {
        return 0;
    }

    // Two modes of operation:
    // 1. Initial Filter (searchResults is empty): Scan all fuzzySnapshots.
    // 2. Subsequent Filter (searchResults has data): Scan only searchResults.

    bool isInitialFilter = searchResults.empty();
    const size_t MAX_RESULTS = 100000;

    if (isInitialFilter) {
        // Path A: Initial Filter - Iterate all snapshots
        const size_t CHUNK_SIZE = 64 * 1024;
        std::vector<uint8_t> currentBuffer(CHUNK_SIZE);

        for (auto& snapshot : fuzzySnapshots) {
            // Check cap
            if (searchResults.size() >= MAX_RESULTS) break;

            size_t size = snapshot.data.size();
            if (size > CHUNK_SIZE) currentBuffer.resize(size);

            struct iovec local_iov = {currentBuffer.data(), size};
            struct iovec remote_iov = {(void*)snapshot.startAddress, size};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead == (ssize_t)size) {
                // Compare int-by-int (4 bytes alignment)
                for (size_t i = 0; i + 4 <= size; i += 4) {
                    if (searchResults.size() >= MAX_RESULTS) break;

                    int oldVal, currentVal;
                    std::memcpy(&oldVal, &snapshot.data[i], sizeof(int));
                    std::memcpy(&currentVal, &currentBuffer[i], sizeof(int));

                    bool match = false;
                    switch (mode) {
                        case 0: match = (currentVal != oldVal); break; // CHANGED
                        case 1: match = (currentVal == oldVal); break; // UNCHANGED
                        case 2: match = (currentVal > oldVal); break;  // INCREASED
                        case 3: match = (currentVal < oldVal); break;  // DECREASED
                    }

                    if (match) {
                        searchResults.push_back((jlong)(snapshot.startAddress + i));
                        // Update snapshot with current value for next comparison
                        std::memcpy(&snapshot.data[i], &currentVal, sizeof(int));
                    }
                }
            }
        }
    } else {
        // Path B: Subsequent Filter - Iterate searchResults
        // This is slower per-address but faster overall if list is small.
        // Optimization: Sort searchResults to batch reads? Assuming sorted.

        auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
            // Find the snapshot containing this address
            // Use binary search for speed since fuzzySnapshots are sorted by address
            auto regionIt = std::lower_bound(fuzzySnapshots.begin(), fuzzySnapshots.end(), addr,
                [](const SnapshotRegion& region, jlong val) {
                    return region.startAddress + region.data.size() <= (uintptr_t)val;
                });

            if (regionIt == fuzzySnapshots.end() || addr < (jlong)regionIt->startAddress) {
                return true; // Address not found in snapshots
            }

            size_t offset = (size_t)(addr - regionIt->startAddress);
            if (offset + 4 > regionIt->data.size()) return true;

            int oldVal;
            std::memcpy(&oldVal, &regionIt->data[offset], sizeof(int));

            int currentVal = 0;
            struct iovec local_iov = {&currentVal, sizeof(int)};
            struct iovec remote_iov = {(void*)(uintptr_t)addr, sizeof(int)};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead != sizeof(int)) return true;

            bool match = false;
            switch (mode) {
                case 0: match = (currentVal != oldVal); break; // CHANGED
                case 1: match = (currentVal == oldVal); break; // UNCHANGED
                case 2: match = (currentVal > oldVal); break;  // INCREASED
                case 3: match = (currentVal < oldVal); break;  // DECREASED
            }

            if (match) {
                // Update snapshot
                std::memcpy(&regionIt->data[offset], &currentVal, sizeof(int));
                return false; // Keep
            }
            return true; // Remove
        });

        searchResults.erase(it, searchResults.end());
    }

    size_t resultCount = searchResults.size();
    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Filter Complete. Remaining: %zu", resultCount);

    // Clamp return value to avoid overflow
    return (jint)std::min(resultCount, (size_t)INT_MAX);
}

extern "C"
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
        return nullptr;
    }

    if (count > 0) {
        env->SetLongArrayRegion(resultArr, 0, count, searchResults.data());
    }

    return resultArr;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_techted89_gameex_NativeScanner_readMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jlong address,
        jint size) {

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

    env->SetByteArrayRegion(result, 0, bytes_read, reinterpret_cast<jbyte*>(buffer.data()));
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jint valueToFind) {

    std::lock_guard<std::mutex> lock(searchResultsMutex);

    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
        int val = 0;
        struct iovec local_iov = {&val, sizeof(int)};
        struct iovec remote_iov = {(void*)(uintptr_t)addr, sizeof(int)};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

        if (bytesRead != sizeof(int)) {
            return true;
        }
        return val != valueToFind;
    });

    searchResults.erase(it, searchResults.end());

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Filter Complete. Remaining: %zu", searchResults.size());
    return (jint)searchResults.size();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemoryString(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jstring queryString) {

    if (queryString == nullptr) return 0;
    const char* queryCStr = env->GetStringUTFChars(queryString, nullptr);
    if (queryCStr == nullptr) return 0;
    std::string query(queryCStr);
    env->ReleaseStringUTFChars(queryString, queryCStr);

    SearchCondition cond = parseSearchQuery(query);
    std::lock_guard<std::mutex> lock(searchResultsMutex);

    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
        int val = 0;
        struct iovec local_iov = {&val, sizeof(int)};
        struct iovec remote_iov = {(void*)(uintptr_t)addr, sizeof(int)};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

        if (bytesRead != sizeof(int)) {
            return true;
        }

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
        return !match; // Remove if NOT match
    });

    searchResults.erase(it, searchResults.end());

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Filter String Complete. Remaining: %zu", searchResults.size());
    return (jint)searchResults.size();
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_techted89_gameex_NativeScanner_getLoadedModules(
        JNIEnv* env,
        jobject /* this */,
        jint pid) {

    std::set<std::string> modules;
    std::string mapsData = readProcMaps(pid);

    if (!mapsData.empty()) {
        std::stringstream ss(mapsData);
        std::string line;
        while (std::getline(ss, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();

            if (line.find(".so") != std::string::npos) {
                size_t lastSpace = line.find_last_of(" \t");
                if (lastSpace != std::string::npos && lastSpace + 1 < line.length()) {
                     std::string path = line.substr(lastSpace + 1);
                     modules.insert(path);
                }
            }
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(modules.size(), stringClass, nullptr);

    int i = 0;
    for (const auto& mod : modules) {
        jstring s = env->NewStringUTF(mod.c_str());
        env->SetObjectArrayElement(result, i++, s);
        env->DeleteLocalRef(s);
    }

    return result;
}
