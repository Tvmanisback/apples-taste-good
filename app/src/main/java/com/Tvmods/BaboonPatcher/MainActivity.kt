package com.Tvmods.BaboonPatcher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val requestCode = 4207

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == requestCode) {
                sendStatus(
                    if (result == PackageManager.PERMISSION_GRANTED)
                        "Shizuku access granted."
                    else
                        "Shizuku permission denied.",
                    result == PackageManager.PERMISSION_GRANTED
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(PatcherBridge(), "Patcher")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    inner class PatcherBridge {

        @JavascriptInterface
        fun checkAccess() {
            executor.execute {
                try {
                    val connected = Shizuku.pingBinder()
                    val granted = connected &&
                        Shizuku.checkSelfPermission() ==
                        PackageManager.PERMISSION_GRANTED

                    sendStatus(
                        when {
                            !connected -> "Shizuku is not running."
                            granted -> "Shizuku is connected and authorized."
                            else -> "Grant Shizuku access to continue."
                        },
                        granted
                    )
                } catch (e: Exception) {
                    sendStatus("Access check failed: ${e.message}", false)
                }
            }
        }

        @JavascriptInterface
        fun grantAccess() {
            runOnUiThread {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus("Start Shizuku first.", false)
                        return@runOnUiThread
                    }

                    if (
                        Shizuku.checkSelfPermission() ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        sendStatus("Access already granted.", true)
                    } else {
                        Shizuku.requestPermission(requestCode)
                    }
                } catch (e: Exception) {
                    sendStatus("Permission request failed: ${e.message}", false)
                }
            }
        }

        @JavascriptInterface
        fun startPatch() {
            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus("Shizuku is not running.", false)
                        return@execute
                    }

                    if (
                        Shizuku.checkSelfPermission() !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        sendStatus("Grant Shizuku permission first.", false)
                        return@execute
                    }

                    sendStatus("Checking OBB directories...", true)

                    val script = """
                        set -eu

                        SRC='/storage/emulated/0/Android/obb/com.BabTeam.Baboon'
                        DST='/storage/emulated/0/Android/obb/com.Tvmods.Baboon'

                        if [ ! -d "${'$'}SRC" ]; then
                            echo "ERROR: Source OBB directory not found: ${'$'}SRC"
                            exit 21
                        fi

                        mkdir -p "${'$'}DST"

                        count=0

                        for file in "${'$'}SRC"/*; do
                            [ -f "${'$'}file" ] || continue

                            name="${'$'}{file##*/}"

                            newname="${'$'}(
                                printf '%s' "${'$'}name" |
                                sed 's/com\.BabTeam\.Baboon/com.Tvmods.Baboon/g'
                            )"

                            cp -f "${'$'}file" "${'$'}DST/${'$'}newname"

                            echo "Copied: ${'$'}name -> ${'$'}newname"

                            count="${'$'}((count + 1))"
                        done

                        if [ "${'$'}count" -eq 0 ]; then
                            echo "ERROR: No OBB files found."
                            exit 22
                        fi

                        echo "COPY_COMPLETE:${'$'}count"
                    """.trimIndent()

                    // Execute Android shell commands using Shizuku's
                    // authorized shell process API.
                    val process = Shizuku.newProcess(
                        arrayOf("sh", "-c", script),
                        null,
                        null
                    )

                    val stdout = StringBuilder()
                    val stderr = StringBuilder()

                    val outThread = Thread {
                        BufferedReader(
                            InputStreamReader(process.inputStream)
                        ).useLines { lines ->
                            lines.forEach {
                                stdout.append(it).append('\n')
                            }
                        }
                    }

                    val errThread = Thread {
                        BufferedReader(
                            InputStreamReader(process.errorStream)
                        ).useLines { lines ->
                            lines.forEach {
                                stderr.append(it).append('\n')
                            }
                        }
                    }

                    outThread.start()
                    errThread.start()

                    val exitCode = process.waitFor()

                    outThread.join()
                    errThread.join()

                    val output = (
                        stdout.toString() + stderr.toString()
                    ).trim()

                    if (
                        exitCode == 0 &&
                        stdout.contains("COPY_COMPLETE:")
                    ) {
                        sendStatus(
                            "Copy completed successfully.\n\n$output",
                            true
                        )
                    } else {
                        sendStatus(
                            "Copy failed (exit code $exitCode).\n\n$output",
                            false
                        )
                    }

                } catch (e: Exception) {
                    sendStatus(
                        "Operation failed: ${e.javaClass.simpleName}: ${e.message}",
                        false
                    )
                }
            }
        }
    }

    private fun sendStatus(message: String, success: Boolean) {
        if (!::webView.isInitialized) return

        val safeMessage = org.json.JSONObject.quote(message)

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onPatcherStatus?.($safeMessage, $success);",
                    null
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        executor.shutdownNow()

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
