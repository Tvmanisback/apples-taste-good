package com.Tvmods.BaboonPatcher

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val permissionRequestCode = 1001
    private val transactionCopyObb = 1

    @Volatile
    private var permissionRequestPending = false

    @Volatile
    private var serviceBinding = false

    @Volatile
    private var patchService: IBinder? = null

    private var serviceBound = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                packageName,
                PatchUserService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("baboonpatcher")
            .tag("BaboonPatcher")
            .version(1)
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener {
                requestCode,
                grantResult ->

            if (requestCode != permissionRequestCode) {
                return@OnRequestPermissionResultListener
            }

            permissionRequestPending = false

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                sendStatus(
                    true,
                    "Authorization confirmed. Patch access is ready."
                )
            } else {
                sendStatus(
                    false,
                    "Permission denied. Authorize BaboonPatcher in Bytezuku."
                )
            }
        }

    private val serviceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                serviceBinding = false

                if (service == null) {
                    patchService = null

                    sendStatus(
                        false,
                        "The patch service returned no connection."
                    )
                    return
                }

                patchService = service

                sendStatus(
                    true,
                    "Patch service connected. Ready to copy OBB files."
                )

                // The service stays available until the app disconnects.
                // Bytezuku itself is not stopped by this callback.
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                patchService = null
                serviceBinding = false

                sendStatus(
                    false,
                    "Patch service disconnected. Check Bytezuku and retry."
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

            addJavascriptInterface(
                PatcherBridge(),
                "Patcher"
            )
        }

        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun sendStatus(
        available: Boolean,
        message: String
    ) {
        if (!::webView.isInitialized) return

        val safeMessage = JSONObject.quote(message)

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread

            webView.evaluateJavascript(
                """
                if (typeof window.onPatcherStatus === 'function') {
                    window.onPatcherStatus($available, $safeMessage);
                } else if (typeof window.onAccessResult === 'function') {
                    window.onAccessResult($available, $safeMessage);
                }
                """.trimIndent(),
                null
            )
        }
    }

    private fun isShizukuConnected(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    private fun hasPatchAccess(): Boolean {
        return try {
            isShizukuConnected() &&
                Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private inner class PatcherBridge {

        @JavascriptInterface
        fun checkAccess() {
            executor.execute {
                when {
                    !isShizukuConnected() -> {
                        sendStatus(
                            false,
                            "Bytezuku is not connected. Start its service first."
                        )
                    }

                    hasPatchAccess() -> {
                        sendStatus(
                            true,
                            "Existing authorization detected. Patch access is ready."
                        )
                    }

                    else -> {
                        sendStatus(
                            false,
                            "Bytezuku is connected, but BaboonPatcher has not been authorized."
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun grantAccess() {
            runOnUiThread {
                try {
                    if (!isShizukuConnected()) {
                        sendStatus(
                            false,
                            "Bytezuku is not connected. Start its service first."
                        )
                        return@runOnUiThread
                    }

                    if (hasPatchAccess()) {
                        sendStatus(
                            true,
                            "Existing authorization detected. Patch access is ready."
                        )
                        return@runOnUiThread
                    }

                    if (permissionRequestPending) {
                        sendStatus(
                            false,
                            "Waiting for the authorization result."
                        )
                        return@runOnUiThread
                    }

                    permissionRequestPending = true

                    sendStatus(
                        false,
                        "Requesting authorization. Approve BaboonPatcher if prompted."
                    )

                    Shizuku.requestPermission(permissionRequestCode)

                } catch (e: Exception) {
                    permissionRequestPending = false

                    sendStatus(
                        false,
                        "Authorization failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }

        @JavascriptInterface
        fun startPatch() {
            executor.execute {
                try {
                    if (!isShizukuConnected()) {
                        sendStatus(
                            false,
                            "Bytezuku is not connected."
                        )
                        return@execute
                    }

                    if (!hasPatchAccess()) {
                        sendStatus(
                            false,
                            "Grant Patch Access before copying files."
                        )
                        return@execute
                    }

                    val existingService = patchService

                    if (existingService != null && existingService.isBinderAlive) {
                        copyObbFiles(existingService)
                        return@execute
                    }

                    if (serviceBinding) {
                        sendStatus(
                            false,
                            "The patch service is still connecting. Try again shortly."
                        )
                        return@execute
                    }

                    sendStatus(
                        false,
                        "Connecting to the authorized patch service..."
                    )

                    runOnUiThread {
                        try {
                            if (!hasPatchAccess()) {
                                sendStatus(
                                    false,
                                    "Shizuku authorization is no longer available."
                                )
                                return@runOnUiThread
                            }

                            serviceBinding = true

                            Shizuku.bindUserService(
                                serviceArgs,
                                serviceConnection
                            )

                            serviceBound = true

                        } catch (e: Exception) {
                            serviceBinding = false

                            sendStatus(
                                false,
                                "Could not connect to the patch service: ${e.message ?: "Unknown error"}"
                            )
                        }
                    }

                } catch (e: Exception) {
                    sendStatus(
                        false,
                        "Patch failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    private fun copyObbFiles(service: IBinder) {
        var data: Parcel? = null
        var reply: Parcel? = null

        try {
            sendStatus(
                false,
                "Copying OBB files..."
            )

            data = Parcel.obtain()
            reply = Parcel.obtain()

            data.writeInterfaceToken(
                "com.Tvmods.BaboonPatcher.PatchUserService"
            )

            data.writeString(
                "/storage/emulated/0/Android/obb/com.BabTeam.Baboon"
            )

            data.writeString(
                "/storage/emulated/0/Android/obb/com.Tvmods.Baboon"
            )

            val handled = service.transact(
                transactionCopyObb,
                data,
                reply,
                0
            )

            if (!handled) {
                throw IllegalStateException(
                    "The patch service did not handle the copy request."
                )
            }

            reply.readException()

            val result = reply.readString()
                ?: throw IllegalStateException(
                    "The patch service returned an empty response."
                )

            val success = result.startsWith("SUCCESS:")

            val message = when {
                success -> result.removePrefix("SUCCESS:")
                result.startsWith("ERROR:") ->
                    result.removePrefix("ERROR:")
                else -> result
            }

            sendStatus(success, message)

        } catch (e: Exception) {
            sendStatus(
                false,
                "OBB copy failed: ${e.message ?: "Unknown error"}"
            )
        } finally {
            data?.recycle()
            reply?.recycle()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)

        if (serviceBound) {
            try {
                // Remove this app's connection without explicitly requesting
                // that the shared UserService be killed.
                Shizuku.unbindUserService(
                    serviceArgs,
                    serviceConnection,
                    false
                )
            } catch (_: Exception) {
                // The service may already have disconnected.
            }
        }

        patchService = null

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Patcher")
            webView.destroy()
        }

        executor.shutdownNow()

        super.onDestroy()
    }
}
