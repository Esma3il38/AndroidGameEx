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
            // Typical ps output: USER PID ... NAME

            // [LEGACY/UNUSED]
            // val parts = line.trim().split(WHITESPACE_REGEX)
            // if (parts.size >= 9) {
            //     val pidStr = parts[1]
            //     val name = parts.last()
            //     try {
            //         val pid = pidStr.toInt()
            //         processes.add(ProcessInfo(pid, name, name, null, true))
            //     } catch (e: NumberFormatException) {
            //     }
            // }

            // Fast manual character-by-character parser avoiding regex
            val trimmedLine = line.trimEnd()
            var pidStr = ""
            var name = ""
            var spaceCount = 0
            var inSpace = true
            var i = 0

            while (i < trimmedLine.length && trimmedLine[i] == ' ') {
                i++
            }

            var currentWordStart = i

            while (i < trimmedLine.length) {
                val c = trimmedLine[i]
                if (c == ' ') {
                    if (!inSpace) {
                        val word = trimmedLine.substring(currentWordStart, i)
                        if (spaceCount == 1) {
                            pidStr = word
                        }
                        spaceCount++
                        inSpace = true
                    }
                } else {
                    if (inSpace) {
                        currentWordStart = i
                        inSpace = false
                    }
                }
                i++
            }

            if (!inSpace) {
                name = trimmedLine.substring(currentWordStart, trimmedLine.length)
                spaceCount++ // Count the last word
                if (spaceCount == 2) {
                     pidStr = name // In case there are only 2 words, edge case
                }
            }

            if (spaceCount >= 9 && pidStr.isNotEmpty() && name.isNotEmpty()) {
                try {
                    val pid = pidStr.toInt()
                    // Default values for raw parsing
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
