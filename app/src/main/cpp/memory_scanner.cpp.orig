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

struct SnapshotRegion {
    uintptr_t startAddress;
    std::vector<uint8_t> data;
};

// Global buffer to store found results (Address List)
std::vector<jlong> searchResults;
std::mutex searchResultsMutex;

// Fuzzy Scan Snapshot Buffer
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
    DataType dataType = TYPE_DWORD;
    // Store as doubles to accommodate all types (except large QWORDs which might lose precision, but sufficient for now)
    // Alternatively, use a union, but parsing logic is simpler with double for now.
    double value1 = 0;
    double value2 = 0; // For range
    int xorKey = 0; // For encrypted
};

// Simple parser for "100~150", "100X8", "100", "123.45"
SearchCondition parseSearchQuery(const std::string& query, DataType forcedType) {
    SearchCondition cond;
    cond.dataType = forcedType;

    // Check for Range (~)
    size_t tildePos = query.find('~');
    if (tildePos != std::string::npos) {
        cond.type = RANGE;
        try {
            cond.value1 = std::stod(query.substr(0, tildePos));
            cond.value2 = std::stod(query.substr(tildePos + 1));
        } catch (...) { cond.type = EXACT; }
        return cond;
    }

    // Check for XOR (X) - Only for Integers usually
    size_t xPos = query.find('X');
    if (xPos != std::string::npos) {
        cond.type = ENCRYPTED_XOR;
        try {
            cond.value1 = std::stod(query.substr(0, xPos));
            cond.xorKey = std::stoi(query.substr(xPos + 1));
        } catch (...) { cond.type = EXACT; }
        return cond;
    }

    // Default Exact
    cond.type = EXACT;
    try {
        cond.value1 = std::stod(query);
    } catch (...) { cond.value1 = 0; }
    return cond;
}

/**
 * @brief Collects readable and writable memory regions for a given process.
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
        char dev[10];
        long inode;
        char path[256] = {0};
        int pos = 0;

        int parsed = sscanf(line.c_str(), "%" SCNxPTR "-%" SCNxPTR " %4s %*s %9s %ld%n",
               &region.startAddress, &region.endAddress, permissions, dev, &inode, &pos);

        if (parsed < 5) continue;

        if (pos > 0 && (size_t)pos < line.length()) {
            const char* p = line.c_str() + pos;
            while (*p == ' ' || *p == '\t') p++;
            strncpy(path, p, sizeof(path) - 1);
            path[sizeof(path) - 1] = '\0';
            size_t len = strlen(path);
            if (len > 0 && path[len-1] == '\n') path[len-1] = '\0';
        } else {
            path[0] = '\0';
        }

        region.isReadable = (permissions[0] == 'r');
        region.isWritable = (permissions[1] == 'w');
        region.isExecutable = (permissions[2] == 'x');

        if (region.isReadable && region.isWritable) {
             std::string pathStr(path);
             // Basic filtering: skip generic system libraries and devices
             // Allow [anon], [heap], [stack]
             if (pathStr.find("/dev/") == std::string::npos &&
                 pathStr.find(".so") == std::string::npos &&
                 pathStr.find(".ttf") == std::string::npos &&
                 pathStr.find(".apk") == std::string::npos &&
                 pathStr.find(".dex") == std::string::npos &&
                 pathStr.find(".jar") == std::string::npos) {
                regions.push_back(region);
             }
        }
    }
    return regions;
}

// Helper to safely write memory using ptrace
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

// Template function to check value match
template <typename T>
bool checkValue(T val, const SearchCondition& cond) {
    switch (cond.type) {
        case EXACT:
            // For floats, use epsilon comparison? For now, strict equality
            if (std::is_floating_point<T>::value)
                return std::abs(val - (T)cond.value1) < 0.0001;
            return val == (T)cond.value1;
        case RANGE:
            return val >= (T)cond.value1 && val <= (T)cond.value2;
        case ENCRYPTED_XOR:
            // Only valid for integral types
            if (std::is_integral<T>::value)
                 return ((long long)val ^ cond.xorKey) == (long long)cond.value1;
            return false;
        default:
            return false;
    }
}

// Core search implementation
int performSearch(int pid, const SearchCondition& cond, std::vector<jlong>& results, bool append = false) {
    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 4096;
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

    for (const auto& region : regions) {
        uintptr_t currentAddr = region.startAddress;
        while (currentAddr < region.endAddress) {
            uintptr_t remaining = region.endAddress - currentAddr;
            size_t readSize = (remaining > CHUNK_SIZE) ? CHUNK_SIZE : (size_t)remaining;

            struct iovec local_iov = {buffer.data(), readSize};
            struct iovec remote_iov = {(void*)currentAddr, readSize};

            ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

            if (bytesRead >= (ssize_t)typeSize) {
                size_t limit = (size_t)bytesRead;
                for (size_t i = 0; i + typeSize <= limit; i += alignment) {
                    bool match = false;
                    switch (cond.dataType) {
                        case TYPE_BYTE: {
                            int8_t val = (int8_t)buffer[i];
                            match = checkValue(val, cond);
                        } break;
                        case TYPE_WORD: {
                            int16_t val; std::memcpy(&val, &buffer[i], 2);
                            match = checkValue(val, cond);
                        } break;
                        case TYPE_DWORD: {
                            int32_t val; std::memcpy(&val, &buffer[i], 4);
                            match = checkValue(val, cond);
                        } break;
                        case TYPE_QWORD: {
                            int64_t val; std::memcpy(&val, &buffer[i], 8);
                            match = checkValue(val, cond);
                        } break;
                        case TYPE_FLOAT: {
                            float val; std::memcpy(&val, &buffer[i], 4);
                            match = checkValue(val, cond);
                        } break;
                        case TYPE_DOUBLE: {
                            double val; std::memcpy(&val, &buffer[i], 8);
                            match = checkValue(val, cond);
                        } break;
                        default: break;
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

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemory(
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

// Legacy support: defaults to DWORD
extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemoryString(
        JNIEnv* env,
        jobject thiz,
        jint pid,
        jstring queryString) {
    return Java_com_techted89_gameex_NativeScanner_searchMemory(env, thiz, pid, queryString, TYPE_DWORD);
}

// Legacy support: searchMemory(int)
extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_searchMemory__II(
        JNIEnv* env,
        jobject thiz,
        jint pid,
        jint valueToFind) {
    std::string query = std::to_string(valueToFind);
    jstring queryString = env->NewStringUTF(query.c_str());
    return Java_com_techted89_gameex_NativeScanner_searchMemory(env, thiz, pid, queryString, TYPE_DWORD);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemory(
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

    std::lock_guard<std::mutex> lock(searchResultsMutex);

    size_t typeSize = 4;
    switch (cond.dataType) {
        case TYPE_BYTE: typeSize = 1; break;
        case TYPE_WORD: typeSize = 2; break;
        case TYPE_DWORD: typeSize = 4; break;
        case TYPE_QWORD: typeSize = 8; break;
        case TYPE_FLOAT: typeSize = 4; break;
        case TYPE_DOUBLE: typeSize = 8; break;
        default: typeSize = 4; break;
    }

    auto it = std::remove_if(searchResults.begin(), searchResults.end(), [&](jlong addr) {
        std::vector<uint8_t> buffer(typeSize);
        struct iovec local_iov = {buffer.data(), typeSize};
        struct iovec remote_iov = {(void*)(uintptr_t)addr, typeSize};

        ssize_t bytesRead = process_vm_readv(pid, &local_iov, 1, &remote_iov, 1, 0);

        if (bytesRead != (ssize_t)typeSize) {
            return true; // Remove if read failed
        }

        bool match = false;
        switch (cond.dataType) {
            case TYPE_BYTE: {
                int8_t val = (int8_t)buffer[0];
                match = checkValue(val, cond);
            } break;
            case TYPE_WORD: {
                int16_t val; std::memcpy(&val, buffer.data(), 2);
                match = checkValue(val, cond);
            } break;
            case TYPE_DWORD: {
                int32_t val; std::memcpy(&val, buffer.data(), 4);
                match = checkValue(val, cond);
            } break;
            case TYPE_QWORD: {
                int64_t val; std::memcpy(&val, buffer.data(), 8);
                match = checkValue(val, cond);
            } break;
            case TYPE_FLOAT: {
                float val; std::memcpy(&val, buffer.data(), 4);
                match = checkValue(val, cond);
            } break;
            case TYPE_DOUBLE: {
                double val; std::memcpy(&val, buffer.data(), 8);
                match = checkValue(val, cond);
            } break;
            default: break;
        }
        return !match;
    });

    searchResults.erase(it, searchResults.end());
    return (jint)searchResults.size();
}

// Legacy filter support
extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterMemoryString(
        JNIEnv* env,
        jobject thiz,
        jint pid,
        jstring queryString) {
    return Java_com_techted89_gameex_NativeScanner_filterMemory(env, thiz, pid, queryString, TYPE_DWORD);
}

// --- Fuzzy Scan Logic ---

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_startFuzzyScan(
        JNIEnv* env,
        jobject,
        jint pid) {

    std::vector<MemoryRegion> regions = getMemoryRegions(pid);
    const size_t CHUNK_SIZE = 64 * 1024; // 64KB chunks for snapshots
    std::vector<uint8_t> buffer(CHUNK_SIZE);

    std::vector<SnapshotRegion> localSnapshots;

    for (const auto& region : regions) {
        uintptr_t current = region.startAddress;
        while (current < region.endAddress) {
            size_t readSize = std::min((size_t)(region.endAddress - current), CHUNK_SIZE);
            struct iovec local = {buffer.data(), readSize};
            struct iovec remote = {(void*)current, readSize};

            ssize_t bytes = process_vm_readv(pid, &local, 1, &remote, 1, 0);
            if (bytes > 0) {
                SnapshotRegion snap;
                snap.startAddress = current;
                snap.data.assign(buffer.begin(), buffer.begin() + bytes);
                localSnapshots.push_back(snap);
            }
            current += readSize;
        }
    }

    {
        std::lock_guard<std::mutex> lock(fuzzyMutex);
        fuzzySnapshots = std::move(localSnapshots);
        // Explicit sort to enable binary search/lookups if needed later, though sequential access is typical
        std::sort(fuzzySnapshots.begin(), fuzzySnapshots.end(), [](const SnapshotRegion& a, const SnapshotRegion& b){
            return a.startAddress < b.startAddress;
        });
    }

    // Clear previous results as we are starting a new fuzzy scan
    {
        std::lock_guard<std::mutex> lock(searchResultsMutex);
        searchResults.clear();
        // Optimistically add ALL addresses? No, that's too many.
        // We wait for the first filter step (CHANGED/UNCHANGED) to populate searchResults.
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Fuzzy Scan Started. Snapshot size: %zu blocks", fuzzySnapshots.size());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_techted89_gameex_NativeScanner_filterFuzzy(
        JNIEnv* env,
        jobject,
        jint pid,
        jint mode) {

    std::lock_guard<std::mutex> fuzzyLock(fuzzyMutex);
    std::lock_guard<std::mutex> resultsLock(searchResultsMutex);

    std::vector<jlong> newResults;
    const size_t ALIGNMENT = 4; // Assume DWORD/Float alignment for fuzzy scan default

    // If searchResults is empty, we scan the entire snapshot (First Filter Step)
    bool firstFilter = searchResults.empty();

    if (firstFilter) {
        for (const auto& snap : fuzzySnapshots) {
            size_t size = snap.data.size();
            std::vector<uint8_t> currentMem(size);
            struct iovec local = {currentMem.data(), size};
            struct iovec remote = {(void*)snap.startAddress, size};

            ssize_t bytes = process_vm_readv(pid, &local, 1, &remote, 1, 0);
            if (bytes != (ssize_t)size) continue;

            for (size_t i = 0; i + 4 <= size; i += ALIGNMENT) {
                int oldVal, newVal;
                memcpy(&oldVal, &snap.data[i], 4);
                memcpy(&newVal, &currentMem[i], 4);

                bool match = false;
                switch (mode) {
                    case FUZZY_CHANGED: match = (oldVal != newVal); break;
                    case FUZZY_UNCHANGED: match = (oldVal == newVal); break;
                    case FUZZY_INCREASED: match = (newVal > oldVal); break;
                    case FUZZY_DECREASED: match = (newVal < oldVal); break;
                }

                if (match) {
                    newResults.push_back((jlong)(snap.startAddress + i));
                    if (newResults.size() >= 100000) goto finish_fuzzy;
                }
            }
        }
    } else {
        // Refine existing results
        for (jlong addr : searchResults) {
            // Find corresponding snapshot block
            // Simple linear search or intelligent lookup.
            // Given sorted snapshots, we can binary search.
            auto it = std::lower_bound(fuzzySnapshots.begin(), fuzzySnapshots.end(), addr,
                [](const SnapshotRegion& region, jlong address) {
                    return region.startAddress + region.data.size() <= (uintptr_t)address;
                });

            if (it != fuzzySnapshots.end() && addr >= (jlong)it->startAddress && addr < (jlong)(it->startAddress + it->data.size())) {
                size_t offset = addr - it->startAddress;
                int oldVal;
                memcpy(&oldVal, &it->data[offset], 4);

                int newVal;
                struct iovec local = {&newVal, 4};
                struct iovec remote = {(void*)addr, 4};
                if (process_vm_readv(pid, &local, 1, &remote, 1, 0) == 4) {
                    bool match = false;
                    switch (mode) {
                        case FUZZY_CHANGED: match = (oldVal != newVal); break;
                        case FUZZY_UNCHANGED: match = (oldVal == newVal); break;
                        case FUZZY_INCREASED: match = (newVal > oldVal); break;
                        case FUZZY_DECREASED: match = (newVal < oldVal); break;
                    }
                    if (match) newResults.push_back(addr);
                }
            }
        }
    }

finish_fuzzy:
    searchResults = std::move(newResults);

    // Update snapshots for NEXT comparison?
    // Usually fuzzy search compares against "Last Scan".
    // So we should update fuzzySnapshots with current values for the kept addresses.
    // However, updating entire blocks is heavy.
    // GameGuardian strategy: "Changed since last scan" vs "Changed since start".
    // For simplicity here, we compare against INITIAL snapshot.
    // To support "Changed since last", we would need to update the snapshot.

    return (jint)searchResults.size();
}


// --- Keep Existing Hooks/Dump/Utils ---

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
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) return JNI_FALSE;

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

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) return JNI_FALSE;
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
        if (it == originalBytesMap.end()) return JNI_FALSE;
        backup = it->second;
    }

    if (pid == getpid()) {
        long pageSize = sysconf(_SC_PAGESIZE);
        void* pageStart = (void*)(targetAddress & ~(pageSize - 1));
        if (mprotect(pageStart, pageSize, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) return JNI_FALSE;
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
    std::string dumpDir(pathC);
    env->ReleaseStringUTFChars(path, pathC);
    return dumpMemoryInternal(pid, from, to, dumpDir) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_enableStealthMode(JNIEnv*, jobject) {
    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Stealth Mode: Activated");
}

// Java_com_techted89_gameex_NativeScanner_startFuzzyScan moved to top with implementation

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
    if (bytes == -1) return env->NewByteArray(0);
    jbyteArray result = env->NewByteArray(bytes);
    env->SetByteArrayRegion(result, 0, bytes, (jbyte*)buffer.data());
    return result;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_techted89_gameex_NativeScanner_getLoadedModules(JNIEnv* env, jobject, jint pid) {
    std::set<std::string> modules;
    std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::ifstream mapsFile(mapsPath);
    if (mapsFile.is_open()) {
        std::string line;
        while (std::getline(mapsFile, line)) {
            if (line.find(".so") != std::string::npos) {
                size_t last = line.find_last_of(" \t");
                if (last != std::string::npos && last + 1 < line.length()) modules.insert(line.substr(last + 1));
            }
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
