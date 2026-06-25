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
#include <cmath>
#include <iostream>
#include <set>
#include <map>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <errno.h>
#include <cmath>

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

// Global fuzzy snapshots
std::vector<SnapshotRegion> fuzzySnapshots;
std::mutex fuzzyMutex;

// Global buffer to store found results (Address List)
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

std::vector<SnapshotRegion> fuzzySnapshots;
std::mutex fuzzyMutex;

// Global map to store original bytes for unhooking
// Key: Target Address, Value: Original Bytes
std::map<jlong, std::vector<uint8_t>> originalBytesMap;
std::mutex originalBytesMutex;

enum DataType {
    TYPE_BYTE = 1,
    TYPE_WORD = 2,
    TYPE_DWORD = 3,
    TYPE_XOR = 4,
    TYPE_QWORD = 5,
    TYPE_FLOAT = 6,
    TYPE_DOUBLE = 7
};

// For fuzzy search snapshot
struct SnapshotRegion {
    uintptr_t startAddress;
    std::vector<uint8_t> data;
};

// Global fuzzy snapshot storage
std::vector<SnapshotRegion> fuzzySnapshots;
std::mutex fuzzyMutex;

enum SearchType {
    EXACT,
    RANGE,
    FUZZY,
    ENCRYPTED_XOR
};

enum DataType {
    TYPE_BYTE = 1,
    TYPE_WORD = 2,
    TYPE_DWORD = 4,
    TYPE_QWORD = 8,
    TYPE_FLOAT = 16,
    TYPE_DOUBLE = 32,
    TYPE_AUTO = 64,
    TYPE_XOR = 128
};

enum FuzzyMode {
    FUZZY_CHANGED = 0,
    FUZZY_UNCHANGED = 1,
    FUZZY_INCREASED = 2,
    FUZZY_DECREASED = 3
};

struct SearchCondition {
    SearchType type = EXACT;
    int64_t iVal1 = 0;
    int64_t iVal2 = 0;
    double fVal1 = 0.0;
    double fVal2 = 0.0;
    int xorKey = 0; // For encrypted
};

// Simple parser for "100~150", "100X8", "100"
SearchCondition parseSearchQuery(const std::string& query, int type) {
    SearchCondition cond;
    cond.dataType = forcedType;
    bool isFloat = (forcedType == TYPE_FLOAT || forcedType == TYPE_DOUBLE);
    bool isAuto = (forcedType == TYPE_AUTO);

    // Check for Range (~)
    size_t tildePos = query.find('~');
    if (tildePos != std::string::npos) {
        cond.type = RANGE;
        if (type == TYPE_FLOAT || type == TYPE_DOUBLE) {
            try {
                cond.fVal1 = std::stod(query.substr(0, tildePos));
                cond.fVal2 = std::stod(query.substr(tildePos + 1));
            } catch (...) { cond.type = EXACT; }
        } else {
            try {
                cond.iVal1 = std::stoll(query.substr(0, tildePos));
                cond.iVal2 = std::stoll(query.substr(tildePos + 1));
            } catch (...) { cond.type = EXACT; }
        }
        return cond;
    }

    // Check for XOR (X) - Only for Integers usually
    size_t xPos = query.find('X');
    if (xPos != std::string::npos) {
        cond.type = ENCRYPTED_XOR;
        try {
            cond.iVal1 = std::stoll(query.substr(0, xPos));
            cond.xorKey = std::stoi(query.substr(xPos + 1));
        } catch (...) { cond.type = EXACT; }
        return cond;
    }

    // Default Exact
    cond.type = EXACT;
    if (type == TYPE_FLOAT || type == TYPE_DOUBLE) {
        try {
            cond.fVal1 = std::stod(query);
        } catch (...) { cond.fVal1 = 0.0; }
    } else {
        try {
            cond.iVal1 = std::stoll(query);
        } catch (...) { cond.iVal1 = 0; }
    }
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
Java_com_techted89_gameex_NativeScanner_searchMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jstring queryString,
        jint type) {
    if (queryString == nullptr) {
        return 0;
    }
}

    const char* queryCStr = env->GetStringUTFChars(queryString, nullptr);
    if (queryCStr == nullptr) {
        return 0;
    }
    std::string query(queryCStr);
    env->ReleaseStringUTFChars(queryString, queryCStr);

    SearchCondition cond = parseSearchQuery(query, type);

    std::vector<jlong> localResults;
    std::vector<MemoryRegion> regions = readProcMaps(pid);

    const size_t CHUNK_SIZE = 65536;
    std::vector<uint8_t> buffer(CHUNK_SIZE);
    int matchCount = 0;
    if (!append) results.clear();

    size_t typeSize = 4;
    size_t alignment = 4;

    switch (cond.dataType) {
        case TYPE_BYTE: typeSize = 1; alignment = 1; break;
        case TYPE_WORD: typeSize = 2; alignment = 2; break;
        case TYPE_DWORD: typeSize = 4; alignment = 4; break;
        case TYPE_QWORD: typeSize = 8; alignment = 8; break;
        case TYPE_FLOAT: typeSize = 4; alignment = 4; break;
        case TYPE_DOUBLE: typeSize = 8; alignment = 8; break;
        default: typeSize = 4; alignment = 4; break;
    }

    int dataSize = 4;
    if (type == TYPE_BYTE) dataSize = 1;
    else if (type == TYPE_WORD) dataSize = 2;
    else if (type == TYPE_DWORD || type == TYPE_FLOAT) dataSize = 4;
    else if (type == TYPE_QWORD || type == TYPE_DOUBLE) dataSize = 8;

    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;
        while (currentAddr < region.endAddress) {
            size_t readSize = std::min((size_t)(region.endAddress - currentAddr), CHUNK_SIZE);

            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead >= dataSize) {
                size_t limit = (size_t)bytesRead;
                limit -= (limit % dataSize);

                for (size_t i = 0; i < limit; i += dataSize) {
                    bool match = false;

                    if (type == TYPE_FLOAT) {
                        float val;
                        memcpy(&val, &buffer[i], 4);
                        if (cond.type == EXACT) match = (std::abs(val - (float)cond.fVal1) < 0.001f);
                        else if (cond.type == RANGE) match = (val >= (float)cond.fVal1 && val <= (float)cond.fVal2);
                    }
                    else if (type == TYPE_DOUBLE) {
                        double val;
                        memcpy(&val, &buffer[i], 8);
                        if (cond.type == EXACT) match = (std::abs(val - cond.fVal1) < 0.000001);
                        else if (cond.type == RANGE) match = (val >= cond.fVal1 && val <= cond.fVal2);
                    }
                    else {
                        int64_t val = 0;
                        if (type == TYPE_BYTE) val = (int8_t)buffer[i];
                        else if (type == TYPE_WORD) { int16_t v; memcpy(&v, &buffer[i], 2); val = v; }
                        else if (type == TYPE_DWORD) { int32_t v; memcpy(&v, &buffer[i], 4); val = v; }
                        else if (type == TYPE_QWORD) { memcpy(&val, &buffer[i], 8); }

                        if (cond.type == EXACT) match = (val == cond.iVal1);
                        else if (cond.type == RANGE) match = (val >= cond.iVal1 && val <= cond.iVal2);
                        else if (cond.type == ENCRYPTED_XOR) match = ((val ^ cond.xorKey) == cond.iVal1);
                    }

                    if (match) {
                        results.push_back((jlong)(currentAddr + i));
                        matchCount++;
                        if (matchCount >= 100000 && !append) goto search_complete;
                    }
                }
            }
            currentAddr += readSize;
        }
    }

search_complete:
    return matchCount;
}

// [LEGACY/UNUSED] Original overloaded searchMemory without signature
// extern "C"
// JNIEXPORT jint JNICALL
// Java_com_techted89_gameex_NativeScanner_searchMemory(
//        JNIEnv* env,
//        jobject thiz,
//        jint pid,
//        jstring valueStr,
//        jint type) {

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemory__ILjava_lang_String_2I(
        JNIEnv* env,
        jobject thiz,
        jint pid,
        jstring valueStr,
        jint type) {
    if (valueStr == nullptr) return 0;
    const char* str = env->GetStringUTFChars(valueStr, nullptr);
    std::string query(str);
    env->ReleaseStringUTFChars(valueStr, str);

    SearchCondition cond = parseSearchQuery(query, (DataType)type);

    std::vector<jlong> localResults;
    int count = performSearch(pid, cond, localResults);

    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults = std::move(localResults);
    }
    return count;
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
#ifdef __aarch64__
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
#else
    __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "installHook is only supported on ARM64");
    return JNI_FALSE;
#endif
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

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) return JNI_FALSE;
    waitpid(pid, nullptr, 0);
    bool success = ptraceWrite(pid, (uintptr_t)targetAddress, backup.data(), backup.size());
    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    if (success) {
        std::lock_guard<std::mutex> lock(originalBytesMutex);
        originalBytesMap.erase(targetAddress);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

// Restore dumpMemoryInternal helper
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
    std::string dumpDir(pathC);
    env->ReleaseStringUTFChars(path, pathC);
    return dumpMemoryInternal(pid, from, to, dumpDir) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_enableStealthMode(JNIEnv*, jobject) {
    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Stealth Mode: Activated");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_startFuzzyScan(
        JNIEnv* env,
        jobject,
        jint pid) {
    std::vector<SnapshotRegion> newSnapshots;
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 65536; // 64KB chunks

    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;
        while (currentAddr < region.endAddress) {
            uintptr_t remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : (size_t)remaining;

            SnapshotRegion snapshot;
            snapshot.startAddress = currentAddr;
            snapshot.data.resize(readSize);

            struct iovec local_iov = {snapshot.data.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead > 0) {
                if ((size_t)bytesRead < readSize) {
                    snapshot.data.resize(bytesRead);
                }
                newSnapshots.push_back(std::move(snapshot));
            }

            currentAddr += readSize;
        }
    }

    // Sort by address just in case
    std::sort(newSnapshots.begin(), newSnapshots.end(), [](const SnapshotRegion& a, const SnapshotRegion& b) {
        return a.startAddress < b.startAddress;
    });

    {
        // Acquire locks to swap state
        std::lock_guard<std::mutex> lock1(fuzzyMutex);
        std::lock_guard<std::mutex> lock2(searchResultsMutex);

        fuzzySnapshots = std::move(newSnapshots);
        searchResults.clear();
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Scan Baseline Captured: %zu regions", fuzzySnapshots.size());
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_techted89_gameex_NativeScanner_getResults(JNIEnv* env, jobject, jint limit) {
    std::lock_guard<std::mutex> lock(searchResultsMutex);
    size_t count = std::min(searchResults.size(), (size_t)limit);
    jlongArray resultArr = env->NewLongArray(count);
    if (count > 0) env->SetLongArrayRegion(resultArr, 0, count, searchResults.data());
    return resultArr;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_techted89_gameex_NativeScanner_readMemory(
        JNIEnv* env, jobject, jint pid, jlong address, jint size) {
    if (size <= 0 || size > 1024 * 1024) return env->NewByteArray(0);
    std::vector<uint8_t> buffer(size);
    struct iovec local = {buffer.data(), (size_t)size};
    struct iovec remote = {(void*)(uintptr_t)address, (size_t)size};
    ssize_t bytes = process_vm_readv(pid, &local, 1, &remote, 1, 0);

    // Fallback if process_vm_readv fails
    if (bytes == -1 || bytes == 0) {
        if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != -1) {
            waitpid(pid, nullptr, 0);
            if (ptraceRead(pid, (uintptr_t)address, buffer.data(), size)) {
                bytes = size; // Assuming we read everything
            }
            ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        }
    }

    if (bytes <= 0) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(bytes);
    env->SetByteArrayRegion(result, 0, bytes, (jbyte*)buffer.data());
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemory(
        JNIEnv* env,
        jobject /* this */,
        jint pid,
        jstring queryString,
        jint type) {
    if (queryString == nullptr) return 0;
    const char* queryCStr = env->GetStringUTFChars(queryString, nullptr);
    if (queryCStr == nullptr) return 0;
    std::string query(queryCStr);
    env->ReleaseStringUTFChars(queryString, queryCStr);

    SearchCondition cond = parseSearchQuery(query, type);
    std::lock_guard<std::mutex> lock(searchResultsMutex);

    int dataSize = 4;
    if (type == TYPE_BYTE) dataSize = 1;
    else if (type == TYPE_WORD) dataSize = 2;
    else if (type == TYPE_DWORD || type == TYPE_FLOAT) dataSize = 4;
    else if (type == TYPE_QWORD || type == TYPE_DOUBLE) dataSize = 8;

    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
        uint8_t temp[8];
        struct iovec local_iov = {temp, (size_t)dataSize};
        struct iovec remote_iov = {(void*)(uintptr_t)addr, (size_t)dataSize};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

        if (bytesRead != dataSize) {
            return true; // Remove if read fails
        }

        bool match = false;
        if (type == TYPE_FLOAT) {
             float val; memcpy(&val, temp, 4);
             if (cond.type == EXACT) match = (std::abs(val - (float)cond.fVal1) < 0.001f);
             else if (cond.type == RANGE) match = (val >= (float)cond.fVal1 && val <= (float)cond.fVal2);
        } else if (type == TYPE_DOUBLE) {
             double val; memcpy(&val, temp, 8);
             if (cond.type == EXACT) match = (std::abs(val - cond.fVal1) < 0.000001);
             else if (cond.type == RANGE) match = (val >= cond.fVal1 && val <= cond.fVal2);
        } else {
             int64_t val = 0;
             if (type == TYPE_BYTE) val = (int8_t)temp[0];
             else if (type == TYPE_WORD) { int16_t v; memcpy(&v, temp, 2); val = v; }
             else if (type == TYPE_DWORD) { int32_t v; memcpy(&v, temp, 4); val = v; }
             else if (type == TYPE_QWORD) { memcpy(&val, temp, 8); }

             if (cond.type == EXACT) match = (val == cond.iVal1);
             else if (cond.type == RANGE) match = (val >= cond.iVal1 && val <= cond.iVal2);
             else if (cond.type == ENCRYPTED_XOR) match = ((val ^ cond.xorKey) == cond.iVal1);
        }
        return !match;
    });

    searchResults.erase(it, searchResults.end());

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Filter Complete. Remaining: %zu", searchResults.size());
    return (jint)searchResults.size();
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_techted89_gameex_NativeScanner_getLoadedModules(JNIEnv* env, jobject, jint pid) {
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
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(modules.size(), strClass, nullptr);
    int i = 0;
    for (const auto& mod : modules) {
        jstring s = env->NewStringUTF(mod.c_str());
        env->SetObjectArrayElement(result, i++, s);
        env->DeleteLocalRef(s);
    }
    return result;
}

// Helper: compareFuzzy
// Returns true if the condition (mode) is met between oldPtr and newPtr based on type
inline bool compareFuzzy(const uint8_t* oldPtr, const uint8_t* newPtr, int mode, int type) {
    switch(type) {
        case 1: { // BYTE
            int8_t o = *(int8_t*)oldPtr;
            int8_t n = *(int8_t*)newPtr;
            switch(mode) {
                case 0: return n != o;
                case 1: return n == o;
                case 2: return n > o;
                case 3: return n < o;
            }
            break;
        }
        case 2: { // WORD
            int16_t o = *(int16_t*)oldPtr;
            int16_t n = *(int16_t*)newPtr;
            switch(mode) {
                case 0: return n != o;
                case 1: return n == o;
                case 2: return n > o;
                case 3: return n < o;
            }
            break;
        }
        case 4: { // DWORD
            int32_t o = *(int32_t*)oldPtr;
            int32_t n = *(int32_t*)newPtr;
            switch(mode) {
                case 0: return n != o;
                case 1: return n == o;
                case 2: return n > o;
                case 3: return n < o;
            }
            break;
        }
        case 16: { // FLOAT
            float o, n;
            memcpy(&o, oldPtr, 4);
            memcpy(&n, newPtr, 4);
            const float EPSILON = 0.0001f;
            switch(mode) {
                case 0: return fabsf(n - o) > EPSILON;
                case 1: return fabsf(n - o) <= EPSILON;
                case 2: return n > o + EPSILON;
                case 3: return n < o - EPSILON;
            }
            break;
        }
        case 64: { // DOUBLE
            double o, n;
            memcpy(&o, oldPtr, 8);
            memcpy(&n, newPtr, 8);
            const double EPSILON = 0.0000001;
            switch(mode) {
                case 0: return fabs(n - o) > EPSILON;
                case 1: return fabs(n - o) <= EPSILON;
                case 2: return n > o + EPSILON;
                case 3: return n < o - EPSILON;
            }
            break;
        }
        case 32: { // QWORD
             int64_t o, n;
             memcpy(&o, oldPtr, 8);
             memcpy(&n, newPtr, 8);
             switch(mode) {
                 case 0: return n != o;
                 case 1: return n == o;
                 case 2: return n > o;
                 case 3: return n < o;
             }
             break;
        }
    }
    return false;
}

// Helper: fuzzyScanInitial (First pass)
void fuzzyScanInitial(int pid, int mode, int type, size_t stride, size_t maxResults) {
    for (auto& snapshot : fuzzySnapshots) {
        if (searchResults.size() >= maxResults) break;

        size_t size = snapshot.data.size();
        std::vector<uint8_t> currentBuffer(size);

        struct iovec local_iov = {currentBuffer.data(), size};
        struct iovec remote_iov = {(void*)snapshot.startAddress, size};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);
        if (bytesRead < (ssize_t)stride) continue;

        size_t limit = (size_t)bytesRead;
        for (size_t i = 0; i + stride <= limit; i += stride) {
            if (compareFuzzy(&snapshot.data[i], &currentBuffer[i], mode, type)) {
                searchResults.push_back((jlong)(snapshot.startAddress + i));
                memcpy(&snapshot.data[i], &currentBuffer[i], stride);
                if (searchResults.size() >= maxResults) return;
            }
        }
    }
}

// Helper: fuzzyScanFilter (Subsequent passes)
void fuzzyScanFilter(int pid, int mode, int type, size_t stride) {
    std::vector<jlong> newResults;
    newResults.reserve(searchResults.size());

    std::sort(searchResults.begin(), searchResults.end());

    size_t resultIdx = 0;
    for (auto& snapshot : fuzzySnapshots) {
        if (resultIdx >= searchResults.size()) break;

        jlong startAddr = (jlong)snapshot.startAddress;
        jlong endAddr = (jlong)(snapshot.startAddress + snapshot.data.size());

        while (resultIdx < searchResults.size() && searchResults[resultIdx] < startAddr) {
            resultIdx++;
        }

        if (resultIdx >= searchResults.size()) break;
        if (searchResults[resultIdx] >= endAddr) continue;

        size_t size = snapshot.data.size();
        std::vector<uint8_t> currentBuffer(size);
        struct iovec local_iov = {currentBuffer.data(), size};
        struct iovec remote_iov = {(void*)snapshot.startAddress, size};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);
        if (bytesRead <= 0) {
             while (resultIdx < searchResults.size() && searchResults[resultIdx] < endAddr) {
                resultIdx++;
            }
            continue;
        }

        while (resultIdx < searchResults.size() && searchResults[resultIdx] < endAddr) {
            jlong addr = searchResults[resultIdx];
            size_t offset = (size_t)(addr - startAddr);

            if (offset + stride <= (size_t)bytesRead) {
                if (compareFuzzy(&snapshot.data[offset], &currentBuffer[offset], mode, type)) {
                    newResults.push_back(addr);
                    memcpy(&snapshot.data[offset], &currentBuffer[offset], stride);
                }
            }
            resultIdx++;
        }
    }
    searchResults = std::move(newResults);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterFuzzy(
        JNIEnv* env,
        jobject,
        jint pid,
        jint mode,
        jint type) {
    std::lock_guard<std::mutex> lock1(fuzzyMutex);
    std::lock_guard<std::mutex> lock2(searchResultsMutex);

    if (fuzzySnapshots.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeScanner", "Fuzzy Filter: No baseline snapshot found.");
        return 0;
    }

    const size_t MAX_RESULTS = 100000;
    size_t stride = 4;
    switch(type) {
        case 1: stride = 1; break;
        case 2: stride = 2; break;
        case 4: stride = 4; break;
        case 16: stride = 4; break;
        case 32: stride = 8; break;
        case 64: stride = 8; break;
        default: stride = 4; break;
    }

    if (searchResults.empty()) {
        fuzzyScanInitial(pid, mode, type, stride, MAX_RESULTS);
    } else {
        fuzzyScanFilter(pid, mode, type, stride);
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Filter Complete. Remaining: %zu", searchResults.size());
    return (jint)searchResults.size();
}
