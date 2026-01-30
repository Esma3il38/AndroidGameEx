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
     * Searches memory using a query string (e.g., "100~200" for range, "100X8" for XOR).
     */
    external fun searchMemoryString(pid: Int, query: String): Int

    /**
     * Starts a fuzzy scan by dumping current memory snapshot.
     */
    external fun startFuzzyScan(pid: Int, dumpPath: String)

    /**
     * Filters the current search results, keeping only those that match the new value.
     *
     * @param pid The target process ID.
     * @param value The new value to filter for.
     * @return The number of remaining matches.
     */
    external fun filterMemory(pid: Int, value: Int): Int

    /**
     * Retrieves a list of loaded modules (libraries) in the target process.
     *
     * @param pid The target process ID.
     * @return An array of strings representing loaded module names/paths.
     */
    external fun getLoadedModules(pid: Int): Array<String>

    /**
     * Enables stealth mode features (hiding process, randomized name).
     */
    external fun enableStealthMode()

    /**
     * Disassembles a Lua script (binary or source) to an assembly listing.
     * @param inPath Input file path.
     * @param outPath Output file path (.asm).
     */
    external fun disassembleScript(inPath: String, outPath: String)

    /**
     * Assembles an assembly listing back into a Lua binary chunk.
     * @param inPath Input assembly file path.
     * @param outPath Output binary file path.
     */
    external fun assembleScript(inPath: String, outPath: String)

    /**
     * Dumps memory regions to files on disk.
     */
    external fun dumpMemory(pid: Int, from: Long, to: Long, path: String): Boolean

    /**
     * Installs a hook at the target address.
     */
    external fun installHook(targetAddress: Long, replacementAddress: Long): Boolean

    /**
     * Removes a previously installed hook.
     */
    external fun removeHook(targetAddress: Long): Boolean

    /**
     * Sets the speed multiplier for the speedhack engine.
     */
    external fun setSpeed(speed: Double): Boolean

    /**
 * Retrieves up to the specified number of addresses discovered by the native scanner.
 *
 * @param limit Maximum number of addresses to return.
 * @return A LongArray of found memory addresses; the array length will be less than or equal to `limit`.
 */
    external fun getResults(limit: Int): LongArray
}