package com.Tvmods.BaboonPatcher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val executor = Executors.newSingleThreadExecutor()
    private val permissionRequestCode = 1001

    @Volatile
    private var checkingAccess = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == permissionRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    sendStatus(true, "Patch access granted.")
                } else {
                    sendStatus(false, "Permission denied. Authorize BaboonPatcher in Bytezuku.")
                }
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
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun sendStatus(success: Boolean, message: String) {
        if (!::webView.isInitialized) return

        val safeMessage = JSONObject.quote(message)

        runOnUiThread {
            webView.evaluateJavascript(
                "if (typeof window.onPatcherStatus === 'function') {" +
                    "window.onPatcherStatus($success, $safeMessage);" +
                    "}",
                null
            )
        }
    }

    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private inner class PatcherBridge {

        @JavascriptInterface
        fun checkAccess() {
            if (checkingAccess) return
            checkingAccess = true

            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus(
                            false,
                            "Bytezuku is not connected. Start Bytezuku and try again."
                        )
                    } else if (hasShizukuPermission()) {
                        sendStatus(
                            true,
                            "Existing Shizuku authorization detected. Ready to patch."
                        )
                    } else {
                        sendStatus(
                            false,
                            "Bytezuku is connected, but BaboonPatcher has not been authorized."
                        )
                    }
                } catch (e: Exception) {
                    sendStatus(
                        false,
                        "Shizuku check failed: ${e.message ?: "Unknown error"}"
                    )
                } finally {
                    checkingAccess = false
                }
            }
        }

        @JavascriptInterface
        fun grantAccess() {
            if (checkingAccess) return
            checkingAccess = true

            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus(
                            false,
                            "Bytezuku is not connected. Open Bytezuku and start its service."
                        )
                        return@execute
                    }

                    if (hasShizukuPermission()) {
                        sendStatus(
                            true,
                            "Patch access is already authorized."
                        )
                        return@execute
                    }

                    runOnUiThread {
                        try {
                            Shizuku.requestPermission(permissionRequestCode)
                        } catch (e: Exception) {
                            sendStatus(
                                false,
                                "Permission request failed: ${e.message ?: "Unknown error"}"
                            )
                        } finally {
                            checkingAccess = false
                        }
                    }
                } catch (e: Exception) {
                    sendStatus(
                        false,
                        "Authorization check failed: ${e.message ?: "Unknown error"}"
                    )
                    checkingAccess = false
                }
            }
        }

        @JavascriptInterface
        fun startPatch() {
            executor.execute {
                if (!hasShizukuPermission()) {
                    sendStatus(
                        false,
                        "Grant Patch Access before copying OBB files."
                    )
                    return@execute
                }

                sendStatus(
                    false,
                    "Authorization is available, but the patch service must be connected before copying files."
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Patcher")
            webView.destroy()
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
