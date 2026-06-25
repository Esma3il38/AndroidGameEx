package com.techted89.gameex
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {

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
            var pid = -1
            var name = ""
            var colIndex = 0
            var i = 0
            val len = line.length

            // Skip leading whitespaces
            while (i < len && line[i] == ' ') i++

            var wordStart = i

            while (i <= len) {
                val isSpace = i == len || line[i] == ' '
                if (isSpace) {
                    if (wordStart < i) {
                        if (colIndex == 1) {
                            try {
                                pid = line.substring(wordStart, i).toInt()
                            } catch (e: NumberFormatException) {
                                pid = -1
                            }
                        }
                        name = line.substring(wordStart, i).trimEnd()
                        colIndex++
                    }
                    wordStart = i + 1
                }
                i++
            }

            if (colIndex >= 9 && pid != -1) {
                processes.add(ProcessInfo(pid, name, name, null, true))
            }
            */
            // FAST MANUAL PARSING
            var pidStr = ""
            var nameStr = ""
            var spaceCount = 0
            var inWord = false
            var currentWordStart = 0

            val len = line.length
            for (i in 0 until len) {
                val c = line[i]
                if (c.isWhitespace()) {
                    if (inWord) {
                        inWord = false
                        if (spaceCount == 1) { // 2nd word is PID
                            pidStr = line.substring(currentWordStart, i)
                        }
                        spaceCount++
                    }
                } else {
                    if (!inWord) {
                        inWord = true
                        currentWordStart = i
                    }
                }
            }
            if (inWord) {
                if (spaceCount == 1) {
                    pidStr = line.substring(currentWordStart, len)
                }
                nameStr = line.substring(currentWordStart, len)
                spaceCount++
            } else if (spaceCount > 0) {
                val trimmedLine = line.trimEnd()
                val lastWordIdx = trimmedLine.lastIndexOf(' ')
                if (lastWordIdx != -1) {
                    nameStr = trimmedLine.substring(lastWordIdx + 1)
                } else {
                    nameStr = trimmedLine
                }
            }

            if (spaceCount >= 9 && pidStr.isNotEmpty() && nameStr.isNotEmpty()) {
                try {
                    val pid = pidStr.toInt()
                    processes.add(ProcessInfo(pid, nameStr, nameStr, null, true))
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
            val installedApps = try {
                pm.getInstalledApplications(0).associateBy { it.packageName }
            } catch (e: Exception) {
                emptyMap<String, ApplicationInfo>()
            }

            processes.addAll(rawProcesses.map { info ->
                val appInfo = installedApps[info.processName]
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
