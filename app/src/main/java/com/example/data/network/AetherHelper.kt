package com.example.data.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Aether runs in Termux and exposes SOCKS5 at 127.0.0.1:1819.
 * This app cannot install/run the binary itself; we deep-link Termux or copy the command.
 */
object AetherHelper {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1819
    const val TERMUX_PACKAGE = "com.termux"

    const val INSTALL_COMMAND =
        "curl -fsSL https://raw.githubusercontent.com/CluvexStudio/aether/main/aether.sh -o aether.sh && chmod +x aether.sh && ./aether.sh install"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun isProxyListening(timeoutMs: Int = 400): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun buildStartCommand(
        protocol: String,
        scan: String,
        noize: String,
        ipMode: String
    ): String {
        val parts = mutableListOf("aether", "--bind", "$SOCKS_HOST:$SOCKS_PORT")
        when (protocol) {
            "wg" -> parts += "--wg"
            "gool" -> parts += "--gool"
            else -> parts += "--masque"
        }
        when (ipMode) {
            "6" -> parts += "-6"
            "dual" -> parts += "--dual"
            else -> parts += "-4"
        }
        parts += listOf("--scan", scan)
        if (noize.isNotBlank() && noize != "default") {
            parts += listOf("--noize", noize)
        }
        parts += "--quick-reconnect"
        return parts.joinToString(" ")
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Aether") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun openTermuxOrStore(context: Context) {
        if (isTermuxInstalled(context)) {
            val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                Toast.makeText(context, "Open Termux manually", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/packages/com.termux/")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.e("AetherHelper", "Cannot open Termux store page", e)
                Toast.makeText(context, "Install Termux from F-Droid first", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Prefer Termux RUN_COMMAND; fall back to clipboard + open Termux. */
    fun runInTermux(context: Context, command: String) {
        if (!isTermuxInstalled(context)) {
            copyToClipboard(context, command)
            openTermuxOrStore(context)
            Toast.makeText(
                context,
                "Install Termux, then paste the command",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val run = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

        try {
            context.startService(run)
            Toast.makeText(
                context,
                "Sent to Termux (enable Allow external apps in Termux if it fails)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.w("AetherHelper", "RUN_COMMAND failed, falling back to clipboard", e)
            copyToClipboard(context, command)
            openTermuxOrStore(context)
            Toast.makeText(
                context,
                "Command copied — paste it in Termux. Enable Termux → Settings → Allow external apps for one-tap run.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
