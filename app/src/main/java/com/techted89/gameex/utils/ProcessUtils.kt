package com.techted89.gameex.utils

import java.io.File

object ProcessUtils {

    fun isProcessPaused(pid: Int): Boolean {
        var process: Process? = null
        return try {
            // Use 'su' to read /proc/[pid]/stat to bypass permission issues on newer Android
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/$pid/stat"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val content = reader.readLine()

            if (content != null) {
                // The state is the 3rd field in /proc/pid/stat
                // Format: PID (comm) state ...
                // comm can contain spaces and parens, so find the last ')'
                val lastParenIndex = content.lastIndexOf(')')
                if (lastParenIndex != -1 && lastParenIndex + 2 < content.length) {
                    val afterName = content.substring(lastParenIndex + 2)
                    val parts = afterName.split(" ")
                    if (parts.isNotEmpty()) {
                        val state = parts[0]
                        return state == "T" // T = Stopped
                    }
                }
            }
            process.waitFor()
            false
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun pauseProcess(pid: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -SIGSTOP $pid")).waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resumeProcess(pid: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -SIGCONT $pid")).waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun randomizePackageName(context: android.content.Context) {
        // Real implementation would involve reinstalling the app with a different package name
        // For this demo, we simulate it by changing the process name visibility in UI
        android.widget.Toast.makeText(context, "Package randomization initiated...", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Triggers library injection via ptrace/dlopen sequence.
     * In a production environment, this would execute a helper binary (e.g., 'injector') with root.
     * Since we lack the binary here, we attempt a standard shell injection command sequence.
     */
    fun injectLibrary(pid: Int, libPath: String): Boolean {
        return try {
            // Sanitize libPath to prevent command injection
            val safeLibPath = libPath.replace("'", "'\"'\"'")

            // Execute real injection command without guards
            // Assumes 'injector' binary is available in PATH or /data/local/tmp
            // This grants full control to the user to attempt injection
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "injector -p $pid -l '$safeLibPath'"))
            val exitCode = process.waitFor()

            // Return true if exit code is 0 (success)
            exitCode == 0
        } catch (e: Exception) {
            // Log failure but do not prevent operation flow
            e.printStackTrace()
            false
        }
    }
}
