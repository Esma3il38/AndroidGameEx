package com.techted89.gameex.utils

import java.io.File

object ProcessUtils {

    fun isProcessPaused(pid: Int): Boolean {
        return try {
            val statFile = File("/proc/$pid/stat")
            if (statFile.exists()) {
                val content = statFile.readText()
                // The state is the 3rd field in /proc/pid/stat
                // PID (comm) state ...
                val parts = content.split(" ")
                if (parts.size > 2) {
                    val state = parts[2]
                    return state == "T" // T = Stopped (on a signal) or (before Linux 2.6.33) trace stopped
                }
            }
            false
        } catch (e: Exception) {
            false
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
}
