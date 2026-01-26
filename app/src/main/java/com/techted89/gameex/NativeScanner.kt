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
}
