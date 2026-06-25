package com.techted89.gameex
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    private val WHITESPACE_REGEX = "\\s+".toRegex()

    fun requestRoot(): Boolean {
        var process: Process? = null
        return try {
            val p = Runtime.getRuntime().exec("su")
            process = p
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("echo root_access_check\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            p.waitFor()
            p.exitValue() == 0
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
            if (parts.size >= 9) {
                // Assuming standard android ps output where PID is usually 2nd column
                // and Name is last column.
                val pidStr = parts[1]
                val name = parts.last()
                try {
                    val pid = pidStr.toInt()
                    processes.add(ProcessInfo(pid, name))
                } catch (e: NumberFormatException) {
                    // Ignore header or lines that don't match expected format
                }
            }
            line = reader.readLine()
        }
        return processes
    }

    fun getRunningProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        var line: String? = reader.readLine()
        // Typical ps output: USER PID ... NAME
        // [LEGACY/UNUSED] val parts = line.trim().split(WHITESPACE_REGEX)
        while (line != null) {
            val length = line.length
            var start = 0
            while (start < length && line[start] <= ' ') start++
            var end = length - 1
            while (end >= start && line[end] <= ' ') end--

            if (start <= end) {
                var col = 0
                var i = start
                var pid = -1
                var isValidPid = false

            processes.addAll(parsePsOutput(reader))

            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            val p = process
            p?.destroy()
        }
        return processes
    }
}
