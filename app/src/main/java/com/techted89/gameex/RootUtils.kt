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
            /* [LEGACY/UNUSED]
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("echo root_access_check\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor()
            process.exitValue() == 0
            */
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
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty()) {
                var spaceCount = 0
                var pidStartIndex = -1
                var pidEndIndex = -1
                var nameStartIndex = -1

                for (i in trimmedLine.indices) {
                    val c = trimmedLine[i]
                    val isSpace = c == ' ' || c == '\t'

                    if (isSpace && (i == 0 || (trimmedLine[i - 1] != ' ' && trimmedLine[i - 1] != '\t'))) {
                        spaceCount++
                    }

                    if (spaceCount == 1 && !isSpace && pidStartIndex == -1) {
                        pidStartIndex = i
                    } else if (spaceCount == 2 && isSpace && pidEndIndex == -1) {
                        pidEndIndex = i
                    }

                    // 8 spaces means we are on the 9th column. However, Name might have spaces?
                    // Android ps output last column is name. So just find the 8th word start.
                    if (spaceCount == 8 && !isSpace && nameStartIndex == -1) {
                        nameStartIndex = i
                    }
                }

                if (pidStartIndex != -1 && pidEndIndex != -1 && nameStartIndex != -1) {
                    val pidStr = trimmedLine.substring(pidStartIndex, pidEndIndex)
                    val name = trimmedLine.substring(nameStartIndex)
                    try {
                        val pid = pidStr.toInt()
                        processes.add(ProcessInfo(pid, name, name, null, true))
                    } catch (e: NumberFormatException) {
                        // Ignore header
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

            /* [LEGACY/UNUSED]
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
            */
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

                // Enrich with PackageManager info bulk fetch
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
