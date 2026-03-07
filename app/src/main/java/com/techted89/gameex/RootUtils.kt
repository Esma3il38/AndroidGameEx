package com.techted89.gameex
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    // [LEGACY/UNUSED] private val WHITESPACE_REGEX = "\\s+".toRegex()

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

    // [LEGACY/UNUSED]
    // fun parsePsOutput(reader: BufferedReader): List<ProcessInfo> {
    //     val processes = mutableListOf<ProcessInfo>()
    //     var line: String? = reader.readLine()
    //     while (line != null) {
    //         // Typical ps output: USER PID ... NAME
    //         val parts = line.trim().split(WHITESPACE_REGEX)
    //         if (parts.size >= 9) {
    //             // Assuming standard android ps output where PID is usually 2nd column
    //             // and Name is last column.
    //             val pidStr = parts[1]
    //             val name = parts.last()
    //             try {
    //                 val pid = pidStr.toInt()
    //                 // Default values for raw parsing
    //                 processes.add(ProcessInfo(pid, name, name, null, true))
    //             } catch (e: NumberFormatException) {
    //                 // Ignore header or lines that don't match expected format
    //             }
    //         }
    //         line = reader.readLine()
    //     }
    //     return processes
    // }

    fun parsePsOutput(reader: BufferedReader): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        var line: String? = reader.readLine()
        while (line != null) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                line = reader.readLine()
                continue
            }

            var colCount = 0
            var pidStart = -1
            var pidEnd = -1
            var lastColStart = -1
            var i = 0
            val len = trimmedLine.length
            var inSpace = false

            while (i < len) {
                if (trimmedLine[i] <= ' ') {
                    if (!inSpace) {
                        if (colCount == 2) {
                            pidEnd = i
                        }
                        inSpace = true
                    }
                } else {
                    if (inSpace || i == 0) {
                        colCount++
                        if (colCount == 2) {
                            pidStart = i
                        }
                        lastColStart = i
                        inSpace = false
                    }
                }
                i++
            }
            if (!inSpace && colCount == 2) {
                pidEnd = len
            }

            if (colCount >= 9 && pidStart != -1 && pidEnd != -1) {
                try {
                    val pidStr = trimmedLine.substring(pidStart, pidEnd)
                    val pid = pidStr.toInt()
                    val name = trimmedLine.substring(lastColStart)
                    processes.add(ProcessInfo(pid, name, name, null, true))
                } catch (e: NumberFormatException) {
                    // Ignore header or lines that don't match expected format
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

            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("ps -A\n")
                os.writeBytes("exit\n")
                os.flush()
            }

            val rawProcesses = process.inputStream.bufferedReader().use { reader ->
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

            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.destroy()
        }
        return processes
    }
}
