package com.techted89.gameex.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import com.techted89.gameex.RootUtils
import java.io.File

object SystemUtils {

    fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("GG Copy", text)
        clipboard.setPrimaryClip(clip)
    }

    fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getTargetPackage(pid: Int): String? {
        return try {
            // Use su via RootUtils to read cmdline of other processes
            // Reading /proc directly requires root if targeting another user's process or due to SELinux
            val cmdline = RootUtils.executeCommand("cat /proc/$pid/cmdline")

            if (cmdline != null && cmdline.isNotEmpty()) {
                // cmdline strings are null-terminated, remove nulls
                cmdline.replace("\u0000", "")
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
