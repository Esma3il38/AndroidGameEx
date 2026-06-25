package com.techted89.gameex
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {
    // [LEGACY/UNUSED]
    // private val WHITESPACE_REGEX = "\\s+".toRegex()

    // [LEGACY/UNUSED]
    // fun requestRoot(): Boolean {
    //     var process: Process? = null
    //     return try {
    //         process = Runtime.getRuntime().exec("su")
    //         DataOutputStream(process.outputStream).use { os ->
    //             os.writeBytes("echo root_access_check\n")
    //             os.writeBytes("exit\n")
    //             os.flush()
    //         }
    //         process.waitFor()
    //         process.exitValue() == 0
    //     } catch (e: Exception) {
    //         false
    //     } finally {
    //         process?.destroy()
    //     }
    // }

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

        // [LEGACY/UNUSED]
        // var line: String? = reader.readLine()
        // while (line != null) {
        //     // Typical ps output: USER PID ... NAME
        //     val parts = line.trim().split(WHITESPACE_REGEX)
        //     if (parts.size >= 9) {
        //         // Assuming standard android ps output where PID is usually 2nd column
        //         // and Name is last column.
        //         val pidStr = parts[1]
        //         val name = parts.last()
        //         try {
        //             val pid = pidStr.toInt()
        //             // Default values for raw parsing
        //             processes.add(ProcessInfo(pid, name, name, null, true))
        //         } catch (e: NumberFormatException) {
        //             // Ignore header or lines that don't match expected format
        //         }
        //     }
        //     line = reader.readLine()
        // }

        var line = reader.readLine()
        while (line != null) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                line = reader.readLine()
                continue
            }

            var wordCount = 0
            var inWord = false
            var pidStart = -1
            var pidEnd = -1
            var nameStart = -1
            var lastCharIndex = trimmedLine.length - 1

            for (i in 0..lastCharIndex) {
                val c = trimmedLine[i]
                if (c == ' ' || c == '\t') {
                    if (inWord) {
                        inWord = false
                        if (wordCount == 2) {
                            pidEnd = i
                        }
                    }
                } else {
                    if (!inWord) {
                        inWord = true
                        wordCount++
                        if (wordCount == 2) {
                            pidStart = i
                        }
                        nameStart = i
                    }
                }
            }

            if (wordCount >= 9 && pidStart != -1 && pidEnd != -1) {
                try {
                    val pidStr = trimmedLine.substring(pidStart, pidEnd)
                    val name = trimmedLine.substring(nameStart)
                    val pid = pidStr.toInt()
                    processes.add(ProcessInfo(pid, name, name, null, true))
                } catch (e: NumberFormatException) {
                    // Ignore
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

            // [LEGACY/UNUSED]
            // // Enrich with PackageManager info
            // val pm = context.packageManager
            // processes.addAll(rawProcesses.map { info ->
            //     try {
            //         val appInfo = pm.getApplicationInfo(info.processName, 0)
            //         val appName = pm.getApplicationLabel(appInfo).toString()
            //         val icon = pm.getApplicationIcon(appInfo)
            //         val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            //         info.copy(appName = appName, icon = icon, isSystemApp = isSystem)
            //     } catch (e: PackageManager.NameNotFoundException) {
            //         // Not an app, keep defaults (isSystemApp=true is reasonable for native processes)
            //         info
            //     } catch (e: Exception) {
            //          info
            //     }
            // })

            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(0)
            val appInfoMap = installedApps.associateBy { it.packageName }

            processes.addAll(rawProcesses.map { info ->
                val appInfo = appInfoMap[info.processName]
                if (appInfo != null) {
                    try {
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        info.copy(appName = appName, icon = icon, isSystemApp = isSystem)
                    } catch (e: Exception) {
                        info
                    }
                } else {
                    info
                }

            p.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.destroy()
        }
        return processes
    }
}
