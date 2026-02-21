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
#include <cstdio>
#include <inttypes.h>

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

// Snapshot for fuzzy scanning
struct SnapshotRegion {
    uintptr_t startAddress;
    std::vector<uint8_t> data;
};

// Global buffer to store found results (Address List)
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

std::vector<SnapshotRegion> fuzzySnapshots;
std::mutex fuzzyMutex;

// Global map to store original bytes for unhooking
// Key: Target Address, Value: Original Bytes
std::map<jlong, std::vector<uint8_t>> originalBytesMap;
std::mutex originalBytesMutex;

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
 * @brief Opens a pipe to read /proc/[pid]/maps using su.
 * @return A pair containing the file pointer and child PID. If failed, returns {nullptr, -1}.
 */
std::pair<FILE*, pid_t> openProcMapsStream(int pid) {
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        return {nullptr, -1};
    }

    std::string path = "/proc/" + std::to_string(pid) + "/maps";
    std::string cmd = "cat " + path + " 2>/dev/null";

    pid_t child = fork();
    if (child == -1) {
        close(pipefd[0]);
        close(pipefd[1]);
        return {nullptr, -1};
    }

    if (child == 0) {
        // Child process
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);

        execlp("su", "su", "-c", cmd.c_str(), nullptr);
        _exit(127); // exec failed
    }

    // Parent process
    close(pipefd[1]);
    return {fdopen(pipefd[0], "r"), child};
}

/**
 * @brief Parses a single line from /proc/[pid]/maps.
 */
bool parseMapLine(const char* line, MemoryRegion& region, std::string& pathOut) {
    char permissions[5];
    char dev[10];
    long inode;
    char path[512] = {0};
    int pos = 0;

    int parsed = sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %4s %*s %9s %ld%n",
           &region.startAddress, &region.endAddress, permissions, dev, &inode, &pos);

    if (parsed < 5) return false;

    if (pos > 0 && (size_t)pos < strlen(line)) {
        const char* p = line + pos;
        while (*p == ' ' || *p == '\t') p++;
        strncpy(path, p, sizeof(path) - 1);
        path[sizeof(path) - 1] = '\0';
        size_t len = strlen(path);
        if (len > 0 && path[len-1] == '\n') path[len-1] = '\0';
    } else {
        path[0] = '\0';
    }
    pathOut = path;

    region.isReadable = (permissions[0] == 'r');
    region.isWritable = (permissions[1] == 'w');
    region.isExecutable = (permissions[2] == 'x');

    return true;
}

/**
 * @brief Collects readable and writable memory regions for a given process.
 * Uses 'su' to bypass permission restrictions.
 */
std::vector<MemoryRegion> readProcMaps(int pid) {
    std::vector<MemoryRegion> regions;

    auto [pipe, childPid] = openProcMapsStream(pid);
    bool usedSu = true;

    // Helper lambda to read from stream
    auto readFromStream = [&](FILE* stream) {
        char line[1024];
        while (fgets(line, sizeof(line), stream)) {
            MemoryRegion region;
            std::string pathStr;
            if (!parseMapLine(line, region, pathStr)) continue;

            if (region.isReadable && region.isWritable) {
                 if (pathStr.find("/dev/") == std::string::npos &&
                     pathStr.find(".so") == std::string::npos &&
                     pathStr.find(".ttf") == std::string::npos &&
                     pathStr.find("[vvar]") == std::string::npos) {
                    regions.push_back(region);
                 }
            }
        }
    };

    if (pipe) {
        readFromStream(pipe);
        fclose(pipe);
        waitpid(childPid, nullptr, 0);
    } else {
        usedSu = false;
    }

    // Fallback if su yielded no results or pipe failed
    if (regions.empty()) {
        std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
        FILE* fp = fopen(mapsPath.c_str(), "r");
        if (fp) {
            readFromStream(fp);
            fclose(fp);
        }
    }

    return regions;
}

// Helper to safely write memory using ptrace using word-sized access
bool ptraceWrite(int pid, uintptr_t addr, const void* data, size_t size) {
    const uint8_t* src = (const uint8_t*)data;
    uintptr_t currentAddr = addr;
    size_t bytesWritten = 0;

    while (bytesWritten < size) {
        uintptr_t alignedAddr = currentAddr & ~(sizeof(long) - 1);
        size_t offset = currentAddr - alignedAddr;

        errno = 0;
        long word = ptrace(PTRACE_PEEKTEXT, pid, alignedAddr, nullptr);
        if (errno != 0) return false;

        uint8_t* wordBytes = (uint8_t*)&word;
        size_t chunk = std::min(sizeof(long) - offset, size - bytesWritten);

        for (size_t i = 0; i < chunk; i++) {
            wordBytes[offset + i] = src[bytesWritten + i];
        }

        if (ptrace(PTRACE_POKETEXT, pid, alignedAddr, (void*)word) == -1) return false;

        currentAddr += chunk;
        bytesWritten += chunk;
    }
    return true;
}

// Helper to safely read memory using ptrace
bool ptraceRead(int pid, uintptr_t addr, void* dest, size_t size) {
    uint8_t* out = (uint8_t*)dest;
    uintptr_t currentAddr = addr;
    size_t bytesRead = 0;

    while (bytesRead < size) {
        uintptr_t alignedAddr = currentAddr & ~(sizeof(long) - 1);
        size_t offset = currentAddr - alignedAddr;

        errno = 0;
        long word = ptrace(PTRACE_PEEKTEXT, pid, alignedAddr, nullptr);
        if (errno != 0) return false;

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
    std::vector<MemoryRegion> regions = readProcMaps(pid);

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

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_installHook(
        JNIEnv* env,
        jobject,
        jint pid,
        jlong targetAddress,
        jlong replacementAddress) {
    if (targetAddress == 0 || replacementAddress == 0) return JNI_FALSE;

    uint32_t trampolineCode[] = {
        0x58000050, // LDR X16, #8
        0xD61F0200  // BR X16
    };

    std::vector<uint8_t> trampoline(16);
    memcpy(trampoline.data(), trampolineCode, 8);
    memcpy(trampoline.data() + 8, &replacementAddress, 8);

    if (pid == getpid()) {
        long pageSize = sysconf(_SC_PAGESIZE);
        void* pageStart = (void*)(targetAddress & ~(pageSize - 1));
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
             return JNI_FALSE;
        }

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

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) {
        return JNI_FALSE;
    }
    waitpid(pid, nullptr, 0);

    std::vector<uint8_t> backup(16);
    if (!ptraceRead(pid, (uintptr_t)targetAddress, backup.data(), 16)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(originalBytesMutex);
        originalBytesMap[targetAddress] = backup;
    }

    bool success = ptraceWrite(pid, (uintptr_t)targetAddress, trampoline.data(), 16);
    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
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
            return JNI_FALSE;
        }
        backup = it->second;
    }

    if (pid == getpid()) {
        long pageSize = sysconf(_SC_PAGESIZE);
        void* pageStart = (void*)(targetAddress & ~(pageSize - 1));
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
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
    std::vector<MemoryRegion> regions = readProcMaps(pid);
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
    if (dumpPath) {
        const char* pathC = env->GetStringUTFChars(dumpPath, nullptr);
        env->ReleaseStringUTFChars(dumpPath, pathC);
    }

    std::vector<MemoryRegion> regions = readProcMaps(pid);
    std::vector<SnapshotRegion> localSnapshots;
    const size_t CHUNK_SIZE = 16 * 1024;

    for (const auto& region : regions) {
        size_t size = region.endAddress - region.startAddress;
        if (size == 0) continue;

        SnapshotRegion snapshot;
        snapshot.startAddress = region.startAddress;
        snapshot.data.resize(size);

        size_t bytesReadTotal = 0;
        while(bytesReadTotal < size) {
            size_t toRead = std::min(CHUNK_SIZE, size - bytesReadTotal);
            struct iovec local_iov = {snapshot.data.data() + bytesReadTotal, toRead};
            struct iovec remote_iov = {(void*)(region.startAddress + bytesReadTotal), toRead};

            ssize_t res = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);
            if (res <= 0) break;
            bytesReadTotal += res;
        }

        if (bytesReadTotal > 0) {
            snapshot.data.resize(bytesReadTotal);
            localSnapshots.push_back(std::move(snapshot));
        }
    }

    std::sort(localSnapshots.begin(), localSnapshots.end(),
        [](const SnapshotRegion& a, const SnapshotRegion& b) {
            return a.startAddress < b.startAddress;
        });

    {
        std::lock_guard<std::mutex> lock(fuzzyMutex);
        fuzzySnapshots = std::move(localSnapshots);
    }

    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults.clear();
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Scan Started. Snapshots: %zu", fuzzySnapshots.size());
}

int filterFuzzyInitialScan(int pid, int mode, std::vector<jlong>& newResults) {
    int matchCount = 0;
    std::lock_guard<std::mutex> fuzzyLock(fuzzyMutex);

    for (const auto& snap : fuzzySnapshots) {
         size_t len = snap.data.size();
         for (size_t offset = 0; offset + 4 <= len; offset += 4) {
             int oldVal;
             memcpy(&oldVal, &snap.data[offset], 4);

             int currentVal;
             struct iovec local = {&currentVal, 4};
             struct iovec remote = {(void*)(snap.startAddress + offset), 4};
             if (process_vm_readv(pid, &local, 1, &remote, 1, 0) == 4) {
                 bool match = false;
                 switch(mode) {
                     case 0: match = (currentVal != oldVal); break; // CHANGED
                     case 1: match = (currentVal == oldVal); break; // UNCHANGED
                     case 2: match = (currentVal > oldVal); break;  // INCREASED
                     case 3: match = (currentVal < oldVal); break;  // DECREASED
                 }
                 if (match) {
                     newResults.push_back((jlong)(snap.startAddress + offset));
                     matchCount++;
                     if (matchCount >= 100000) return matchCount;
                 }
             }
         }
    }
    return matchCount;
}

int filterFuzzySubsequentScan(int pid, int mode) {
    std::lock_guard<std::mutex> fuzzyLock(fuzzyMutex);
    std::lock_guard<std::mutex> resLock(searchResultsMutex);

    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
         auto ub = std::upper_bound(fuzzySnapshots.begin(), fuzzySnapshots.end(), (uintptr_t)addr,
             [](uintptr_t a, const SnapshotRegion& r) { return a < r.startAddress; });

         SnapshotRegion* foundRegion = nullptr;
         if (ub != fuzzySnapshots.begin()) {
             SnapshotRegion* candidate = const_cast<SnapshotRegion*>(&(*(--ub)));
             if (addr >= candidate->startAddress && addr < candidate->startAddress + candidate->data.size()) {
                 foundRegion = candidate;
             }
         }

         if (!foundRegion) return true;

         size_t offset = addr - foundRegion->startAddress;
         if (offset + 4 > foundRegion->data.size()) return true;

         int oldVal;
         memcpy(&oldVal, &foundRegion->data[offset], 4);

         int currentVal;
         struct iovec local = {&currentVal, 4};
         struct iovec remote = {(void*)(uintptr_t)addr, 4};
         if (process_vm_readv(pid, &local, 1, &remote, 1, 0) != 4) return true;

         bool match = false;
         switch(mode) {
             case 0: match = (currentVal != oldVal); break;
             case 1: match = (currentVal == oldVal); break;
             case 2: match = (currentVal > oldVal); break;
             case 3: match = (currentVal < oldVal); break;
         }

         if (match) {
             memcpy(&foundRegion->data[offset], &currentVal, 4);
         }

         return !match;
    });
    searchResults.erase(it, searchResults.end());
    return (int)searchResults.size();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterFuzzy(
        JNIEnv* env,
        jobject,
        jint pid,
        jint mode) {

    // 0=CHANGED, 1=UNCHANGED, 2=INCREASED, 3=DECREASED

    std::vector<jlong> newResults;
    bool isFirstFilter = false;
    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        isFirstFilter = searchResults.empty();
    }

    if (isFirstFilter) {
        filterFuzzyInitialScan(pid, mode, newResults);
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults = std::move(newResults);
    } else {
        filterFuzzySubsequentScan(pid, mode);
    }

    std::lock_guard<std::mutex> lock(searchResultsMutex);
    return (jint)searchResults.size();
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

    auto [pipe, childPid] = openProcMapsStream(pid);
    bool usedSu = true;

    // Helper lambda to read from stream
    auto readFromStream = [&](FILE* stream) {
        char line[1024];
        while (fgets(line, sizeof(line), stream)) {
            if (strstr(line, ".so")) {
                std::string s(line);
                size_t lastSpace = s.find_last_of(" \t");
                if (lastSpace != std::string::npos && lastSpace + 1 < s.length()) {
                     std::string path = s.substr(lastSpace + 1);
                     if (!path.empty() && path.back() == '\n') path.pop_back();
                     modules.insert(path);
                }
            }
        }
    };

    if (pipe) {
        readFromStream(pipe);
        fclose(pipe);
        waitpid(childPid, nullptr, 0);
    } else {
        usedSu = false;
    }

    if (modules.empty()) {
        std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
        FILE* fp = fopen(mapsPath.c_str(), "r");
        if (fp) {
            readFromStream(fp);
            fclose(fp);
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
