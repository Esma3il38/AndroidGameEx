package com.techted89.gameex
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    fun requestRoot(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("echo root_access_check\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun getRunningProcesses(): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        // Execute ps -A via su to see all processes
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            os.writeBytes("ps -A\n")
            os.writeBytes("exit\n")
            os.flush()

            var line: String? = reader.readLine()
            while (line != null) {
                // Typical ps output: USER PID ... NAME
                val parts = line!!.trim().split("\\s+".toRegex())
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
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to non-root ps if su fails?
            // For now, return what we have or empty list which prompts user to check root.
        } finally {
            process?.destroy()
        }
        return processes
    }
}
