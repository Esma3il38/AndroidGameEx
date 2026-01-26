package com.techted89.gameex

object NativeScanner {
    init {
        System.loadLibrary("native-scanner")
    }

    /**
 * Read a sequence of bytes from the memory of a target process.
 *
 * @param pid The target process identifier (PID).
 * @param address The starting absolute memory address in the target process to read from.
 * @param size The number of bytes to read.
 * @return A byte array containing the bytes read; its length will be equal to `size` when the read succeeds. 
 */
external fun readMemory(pid: Int, address: Long, size: Int): ByteArray

    /**
 * Searches the target process's memory for occurrences of a 4-byte integer value.
 *
 * @param pid The target process ID.
 * @param value The 4-byte integer value to search for.
 * @return The number of matches found.
 */
    external fun searchMemory(pid: Int, value: Int): Int

    /**
 * Retrieves up to the specified number of addresses discovered by the native scanner.
 *
 * @param limit Maximum number of addresses to return.
 * @return A LongArray of found memory addresses; the array length will be less than or equal to `limit`.
 */
    external fun getResults(limit: Int): LongArray
}