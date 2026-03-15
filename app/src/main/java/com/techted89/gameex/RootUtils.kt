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

                while (i <= end) {
                    if (line[i] > ' ') {
                        if (col == 1) {
                            pid = 0
                            isValidPid = true
                            while (i <= end && line[i] > ' ') {
                                val c = line[i]
                                if (c in '0'..'9') {
                                    pid = pid * 10 + (c - '0')
                                } else {
                                    isValidPid = false
                                }
                                i++
                            }
                        } else {
                            while (i <= end && line[i] > ' ') {
                                i++
                            }
                        }
                        col++
                    } else {
                        while (i <= end && line[i] <= ' ') {
                            i++
                        }
                    }
                }

                if (col >= 9 && isValidPid) {
                    var nameStart = end
                    while (nameStart >= start && line[nameStart] > ' ') {
                        nameStart--
                    }
                    val name = line.substring(nameStart + 1, end + 1)
                    processes.add(ProcessInfo(pid, name, name, null, true))
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
