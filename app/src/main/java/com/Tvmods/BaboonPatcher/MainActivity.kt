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

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                val granted =
                    grantResult == PackageManager.PERMISSION_GRANTED

                runOnUiThread {
                    webView.evaluateJavascript(
                        "onAccessResult($granted)",
                        null
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(PatcherBridge(), "Patcher")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    inner class PatcherBridge {

        @JavascriptInterface
        fun grantAccess() {
            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus("Shizuku is not running.")
                        return@execute
                    }

                    if (Shizuku.checkSelfPermission() ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        sendAccessResult(true)
                    } else {
                        Shizuku.requestPermission(REQUEST_CODE)
                    }
                } catch (e: Exception) {
                    sendStatus(
                        "Permission error: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }

        @JavascriptInterface
        fun startPatch() {
            executor.execute {
                var process: Process? = null

                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus("ERROR: Shizuku is not running.")
                        return@execute
                    }

                    if (Shizuku.checkSelfPermission() !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        sendStatus("ERROR: Grant Shizuku permission first.")
                        return@execute
                    }

                    sendStatus("Checking OBB directories...")

                    val script = """
                        set -eu

                        SRC='/storage/emulated/0/Android/obb/com.BabTeam.Baboon'
                        DST='/storage/emulated/0/Android/obb/com.Tvmods.Baboon'

                        if [ ! -d "${'$'}SRC" ]; then
                            echo 'ERROR: Source OBB directory not found.'
                            exit 2
                        fi

                        mkdir -p "${'$'}DST"

                        FOUND=0

                        for FILE in "${'$'}SRC"/*; do
                            [ -f "${'$'}FILE" ] || continue
                            FOUND=1

                            NAME="${'$'}{FILE##*/}"
                            NEWNAME=$(printf '%s' "${'$'}NAME" |
                                sed 's/com\.BabTeam\.Baboon/com.Tvmods.Baboon/g')

                            cp -f "${'$'}FILE" "${'$'}DST/${'$'}NEWNAME"
                        done

                        if [ "${'$'}FOUND" -eq 0 ]; then
                            echo 'ERROR: No OBB files found.'
                            exit 3
                        fi

                        echo 'COPY_COMPLETE'
                    """.trimIndent()

                    process = Shizuku.newProcess(
                        arrayOf("sh", "-c", script),
                        null,
                        null
                    )

                    val stdout = BufferedReader(
                        InputStreamReader(process.inputStream)
                    ).use { reader ->
                        reader.readText()
                    }

                    val stderr = BufferedReader(
                        InputStreamReader(process.errorStream)
                    ).use { reader ->
                        reader.readText()
                    }

                    val exitCode = process.waitFor()

                    if (exitCode == 0 && stdout.contains("COPY_COMPLETE")) {
                        sendStatus("OBB copy completed successfully.")
                    } else {
                        sendStatus(
                            "ERROR: Command failed (exit $exitCode).\n" +
                                stdout + "\n" + stderr
                        )
                    }
                } catch (e: Exception) {
                    process?.destroy()

                    sendStatus(
                        "ERROR: ${e.message ?: "Command execution failed"}"
                    )
                }
            }
        }
    }

    private fun sendAccessResult(granted: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "onAccessResult($granted)",
                null
            )
        }
    }

    private fun sendStatus(message: String) {
        val escaped = message
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        runOnUiThread {
            webView.evaluateJavascript(
                "onPatchStatus('$escaped')",
                null
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        executor.shutdownNow()

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Patcher")
            webView.destroy()
        }

        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CODE = 1001
    }
}
