package com.techted89.gameex

object NativeScanner {
    // Load the C++ library on initialization
    init {
        System.loadLibrary("native-scanner")
    }

    /**
     * Reads memory from a specific process ID.
     * @param pid The target process ID.
     * @param address The memory address (hex) to read from.
     * @param size The number of bytes to read.
     */
    external fun readMemory(pid: Int, address: Long, size: Int): ByteArray

    /**
     * Parses /proc/[pid]/maps to find valid memory regions.
     * @param pid The target process ID.
     * @return Array of MemoryRegion objects.
     */
    external fun getMemoryRegions(pid: Int): Array<MemoryRegion>
}
