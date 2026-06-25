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

    // [LEGACY/UNUSED]
    // fun parsePsOutput(reader: BufferedReader): List<ProcessInfo> {
    //     val processes = mutableListOf<ProcessInfo>()
    //     var line: String? = reader.readLine()
    //     while (line != null) {
    //         val parts = line.trim().split(WHITESPACE_REGEX)
    //         if (parts.size >= 9) {
    //             val pidStr = parts[1]
    //             val name = parts.last()
    //             try {
    //                 val pid = pidStr.toInt()
    //                 processes.add(ProcessInfo(pid, name, name, null, true))
    //             } catch (e: NumberFormatException) {
    //             }
    //         }
    //         line = reader.readLine()
    //     }
    //     return processes
    // }

    fun parsePsOutput(reader: BufferedReader): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        var line: String? = reader.readLine()
        // Typical ps output: USER PID ... NAME
        // [LEGACY/UNUSED] val parts = line.trim().split(WHITESPACE_REGEX)
        while (line != null) {
            val len = line.length
            var col = 0
            var inSpace = true
            var pidStart = -1
            var pidEnd = -1
            var nameStart = -1

            for (i in 0 until len) {
                val c = line[i]
                val isSpace = c <= ' '
                if (inSpace && !isSpace) {
                    // Transition to word
                    col++
                    inSpace = false
                    if (col == 2) {
                        pidStart = i
                    }
                    // Name is the last column, we track every word start
                    // and consider it nameStart until we reach the end of line
                    if (col >= 9) {
                        if (nameStart == -1) {
                            nameStart = i
                        }
                    }
                } else if (!inSpace && isSpace) {
                    // Transition to space
                    inSpace = true
                    if (col == 2 && pidEnd == -1) {
                        pidEnd = i
                    }
                }
            }

            if (pidStart != -1 && pidEnd != -1 && nameStart != -1) {
                val pidStr = line.substring(pidStart, pidEnd)
                // Trim trailing spaces manually
                var nameEnd = len
                while (nameEnd > nameStart && line[nameEnd - 1] <= ' ') {
                    nameEnd--
                }
                val name = line.substring(nameStart, nameEnd)

                try {
                    val pidStr = trimmedLine.substring(pidStart, pidEnd)
                    val pid = pidStr.toInt()
                    processes.add(ProcessInfo(pid, name, name, null, true))
                } catch (e: NumberFormatException) {
                }
            }

            line = reader.readLine()
        }

        return processes
    }

    fun getRunningProcesses(context: Context): List<ProcessInfo> {
        val processes = mutableListOf<ProcessInfo>()
        // [LEGACY/UNUSED]
        // var process: Process? = null
        // try {
        //     process = Runtime.getRuntime().exec("su")
        //
        //     DataOutputStream(process.outputStream).use { os ->
        //         os.writeBytes("ps -A\n")
        //         os.writeBytes("exit\n")
        //         os.flush()
        //     }
        //
        //     val rawProcesses = process.inputStream.bufferedReader().use { reader ->
        //         parsePsOutput(reader)
        //     }

        var process: Process? = null
        try {
            val p = Runtime.getRuntime().exec("su")
            process = p

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
            val installedApps = pm.getInstalledApplications(0).associateBy { it.packageName }
            processes.addAll(rawProcesses.map { info ->
                try {
                    val appInfo = installedApps[info.processName]
                    if (appInfo != null) {
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        info.copy(appName = appName, icon = icon, isSystemApp = isSystem)
                    } else {
                        // Not an app, keep defaults (isSystemApp=true is reasonable for native processes)
                        info
                    }
                } catch (e: Exception) {
                     info
                }
            })

            val p = process
            p?.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            val p = process
            p?.destroy()
        }
        return processes
    }
}
