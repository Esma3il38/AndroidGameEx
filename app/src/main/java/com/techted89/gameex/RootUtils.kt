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
            /* [LEGACY/UNUSED]
            // Typical ps output: USER PID ... NAME
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size >= 9) {
                // Assuming standard android ps output where PID is usually 2nd column
                // and Name is last column.
                val pidStr = parts[1]
                val name = parts.last()
                try {
                    val pid = pidStr.toInt()
                    // Default values for raw parsing
                    processes.add(ProcessInfo(pid, name, name, null, true))
                } catch (e: NumberFormatException) {
                    // Ignore header or lines that don't match expected format
                }
            }
            */
            val trimmedLine = line.trimEnd()
            val len = trimmedLine.length
            var i = 0

            // Skip leading whitespace
            while (i < len && trimmedLine[i] <= ' ') i++

            if (i < len) {
                // Skip word 1: USER
                while (i < len && trimmedLine[i] > ' ') i++
                while (i < len && trimmedLine[i] <= ' ') i++

                if (i < len) {
                    // Word 2: PID
                    val pidStart = i
                    while (i < len && trimmedLine[i] > ' ') i++
                    val pidEnd = i

                    var wordCount = 2
                    var lastWordStart = -1

                    while (i < len) {
                        while (i < len && trimmedLine[i] <= ' ') i++
                        if (i < len) {
                            wordCount++
                            lastWordStart = i
                            while (i < len && trimmedLine[i] > ' ') i++
                        }
                    }

                    if (wordCount >= 9 && lastWordStart != -1) {
                        val pidStr = trimmedLine.substring(pidStart, pidEnd)
                        val name = trimmedLine.substring(lastWordStart, len)
                        try {
                            val pid = pidStr.toInt()
                            processes.add(ProcessInfo(pid, name, name, null, true))
                        } catch (e: NumberFormatException) {
                            // Ignore header or lines that don't match expected format
                        }
                    }
                }
            }
            line = reader.readLine()
        }
        return processes
    }

    fun getRunningProcesses(context: Context): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        // Execute ps -A via su to see all processes
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val p = process

            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes("ps -A\n")
                os.writeBytes("exit\n")
                os.flush()
            }

            val rawProcesses = p.inputStream.bufferedReader().use { reader ->
                parsePsOutput(reader)
            }

            // Enrich with PackageManager info
            val pm = context.packageManager
            processes.addAll(rawProcesses.map { info ->
                try {
                    val appInfo = pm.getApplicationInfo(info.processName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    info.copy(appName = appName, icon = icon, isSystemApp = isSystem)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Not an app, keep defaults (isSystemApp=true is reasonable for native processes)
                    info
                } catch (e: Exception) {
                     info
                }
            })

            p.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.destroy()
        }
        return processes
    }
}
