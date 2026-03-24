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
            var start = 0
            val len = line.length
            while (start < len && line[start].isWhitespace()) {
                start++
            }
            var end = len - 1
            while (end >= start && line[end].isWhitespace()) {
                end--
            }

            if (start <= end) {
                val trimmed = line.substring(start, end + 1)

                var spaceCount = 0
                var pidStr = ""
                var nameStr = ""
                var lastSpaceIdx = -1

                var i = 0
                val trimmedLen = trimmed.length
                while (i < trimmedLen) {
                    if (trimmed[i].isWhitespace()) {
                        if (i == 0 || !trimmed[i - 1].isWhitespace()) {
                            spaceCount++
                            if (spaceCount == 1) {
                                var pidStart = i + 1
                                while (pidStart < trimmedLen && trimmed[pidStart].isWhitespace()) {
                                    pidStart++
                                }
                                var pidEnd = pidStart
                                while (pidEnd < trimmedLen && !trimmed[pidEnd].isWhitespace()) {
                                    pidEnd++
                                }
                                if (pidStart < pidEnd) {
                                    pidStr = trimmed.substring(pidStart, pidEnd)
                                }
                            }
                            lastSpaceIdx = i
                        }
                    }
                    i++
                }

                if (spaceCount >= 8 && lastSpaceIdx != -1) {
                    var nameStart = lastSpaceIdx + 1
                    while (nameStart < trimmedLen && trimmed[nameStart].isWhitespace()) {
                        nameStart++
                    }
                    if (nameStart < trimmedLen) {
                        nameStr = trimmed.substring(nameStart)
                        try {
                            val pid = pidStr.toInt()
                            processes.add(ProcessInfo(pid, nameStr, nameStr, null, true))
                        } catch (e: NumberFormatException) {
                            // Ignore
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
