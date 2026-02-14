package com.techted89.gameex.utils

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object ProcessUtils {

    fun isProcessPaused(pid: Int): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/$pid/stat"))

            val content = process.inputStream.bufferedReader().use { reader ->
                reader.readLine()
            }

            process.waitFor()

            if (content != null) {
                // The state is the 3rd field in /proc/pid/stat
                // PID (comm) state ...
                // Note: comm can contain spaces and parentheses.
                // Robust parsing finds the last ')' and parses from there.
                val lastParen = content.lastIndexOf(')')
                if (lastParen != -1 && lastParen + 2 < content.length) {
                    val stateChar = content[lastParen + 2]
                    return stateChar == 'T' // T = Stopped (on a signal) or (before Linux 2.6.33) trace stopped
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
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
            // Hex-encode the path to bypass shell interpretation entirely
            // 'xxd -r -p' reverses the hex dump back to binary/text
            val hexPath = libPath.toByteArray().joinToString("") { "%02x".format(it) }

            // Construct command: echo <hex> | xxd -r -p | xargs -0 -I {} injector -p <pid> -l {}
            // Note: xargs -0 might not be available on all Android toybox implementations.
            // Simpler alternative: store path in a temp var or use command substitution if injector supports it.
            // Assuming injector takes -l <path>, we can use $(printf ...) to decode safely.

            // Robust command using printf to decode hex strictly
            val cmd = "injector -p $pid -l \"$(printf '\\x%s' $(echo $hexPath | sed 's/../& /g'))\""

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
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
