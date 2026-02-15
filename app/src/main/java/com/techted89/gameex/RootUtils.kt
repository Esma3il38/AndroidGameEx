package com.techted89.gameex
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    fun requestRoot(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("echo root_access_check\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun parsePsOutput(reader: BufferedReader): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        var line: String? = reader.readLine()
        while (line != null) {
            // Typical ps output: USER PID ... NAME
            val parts = line.trim().split(WHITESPACE_REGEX)
            // Ensure enough columns for PID (index 1) and Name (last)
            // PID is usually at index 1. Toybox ps has 8 columns.
            if (parts.size >= 8) {
                val pidStr = parts[1]
                val name = parts.last()
                try {
                    val pid = pidStr.toInt()
                    // Filter out header (where pidStr is "PID")
                    processes.add(ProcessInfo(pid, name))
                } catch (e: NumberFormatException) {
                    // Ignore lines that don't have a valid integer PID
                }
            }
            line = reader.readLine()
        }
        return processes
    }

    fun getRunningProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        try {
            // Use ProcessBuilder for safer execution and simpler stream handling
            val process = ProcessBuilder("su", "-c", "ps -A").start()

            process.inputStream.bufferedReader().use { reader ->
                processes.addAll(parsePsOutput(reader))
            }

            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return processes
    }

    fun executeCommand(cmd: String): String? {
        return try {
            val process = ProcessBuilder("su", "-c", cmd).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() == 0) output.trim() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
