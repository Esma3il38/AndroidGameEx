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

    fun requestRoot(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val p = process
            if (p != null) {
                DataOutputStream(p.outputStream).use { os ->
                    os.writeBytes("echo root_access_check\n")
                    os.writeBytes("exit\n")
                    os.flush()
                }
                p.waitFor()
                p.exitValue() == 0
            } else {
                false
            }
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
            val trimmedLine = line.trimEnd()
            var wordCount = 0
            var wordStart = -1
            var pidStr = ""
            var nameStr = ""

            for (i in 0 until trimmedLine.length) {
                val c = trimmedLine[i]
                val isSpace = c == ' ' || c == '\t'
                if (!isSpace) {
                    if (wordStart == -1) {
                        wordStart = i
                    }
                } else {
                    if (wordStart != -1) {
                        wordCount++
                        if (wordCount == 2) {
                            pidStr = trimmedLine.substring(wordStart, i)
                        }
                        nameStr = trimmedLine.substring(wordStart, i)
                        wordStart = -1
                    }
                }
            }
            if (wordStart != -1) {
                wordCount++
                if (wordCount == 2) {
                    pidStr = trimmedLine.substring(wordStart, trimmedLine.length)
                }
                nameStr = trimmedLine.substring(wordStart, trimmedLine.length)
            }

            if (wordCount >= 9) {
                try {
                    val pid = pidStr.toInt()
                    // Default values for raw parsing
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
        // Execute ps -A via su to see all processes
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val p = process

            if (p != null) {
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.destroy()
        }
        return processes
    }
}
