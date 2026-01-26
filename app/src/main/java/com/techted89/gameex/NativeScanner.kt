package com.techted89.gameex

object NativeScanner {
    init {
        System.loadLibrary("native-scanner")
    }

    external fun readMemory(pid: Int, address: Long, size: Int): ByteArray

    /**
     * Scans memory for a 4-byte integer.
     * @param pid Target Process ID
     * @param value The integer to find
     * @return The number of matches found.
     */
    external fun searchMemory(pid: Int, value: Int): Int

    /**
     * Retrieves the found addresses from the native vector.
     * @param limit Max number of results to fetch (e.g., 100 for UI display)
     */
    external fun getResults(limit: Int): LongArray
}
