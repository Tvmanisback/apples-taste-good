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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val executor = Executors.newSingleThreadExecutor()

    private val permissionRequestCode = 1001

    private val serviceDescriptor =
        "com.Tvmods.BaboonPatcher.PatchUserService"

    private val serviceTransactionCode = 1

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                this,
                PatchUserService::class.java
            )
        )
            .daemon(false)
            .processNameSuffix("patch_service")
            .tag("BaboonPatcher")
            .version(1)
    }

    private var patchServiceBinder: IBinder? = null
    private var serviceBound = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->

            if (requestCode != permissionRequestCode) {
                return@OnRequestPermissionResultListener
            }

            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    sendStatus(
                        true,
                        "Patch access granted. You can now copy the OBB files."
                    )
                } else {
                    sendStatus(
                        false,
                        "Access was not granted. Authorize BaboonPatcher in Bytezuku/Shizuku first."
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            addJavascriptInterface(
                PatcherBridge(),
                "Patcher"
            )

            webViewClient = WebViewClient()
        }

        setContentView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun sendStatus(
        success: Boolean,
        message: String
    ) {
        if (!::webView.isInitialized) return

        val safeMessage = JSONObject.quote(message)

        webView.post {
            webView.evaluateJavascript(
                "window.onPatcherStatus($success, $safeMessage);",
                null
            )
        }
    }

    private fun hasPatchAccess(): Boolean {
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
            executor.execute {
                val connected = try {
                    Shizuku.pingBinder()
                } catch (_: Exception) {
                    false
                }

                when {
                    !connected -> {
                        sendStatus(
                            false,
                            "Bytezuku/Shizuku is not connected. Start it first."
                        )
                    }

                    hasPatchAccess() -> {
                        sendStatus(
                            true,
                            "Existing Bytezuku/Shizuku authorization detected. Patch access is ready."
                        )
                    }

                    else -> {
                        sendStatus(
                            false,
                            "Bytezuku/Shizuku is connected, but BaboonPatcher has not been authorized."
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun grantAccess() {
            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus(
                            false,
                            "Start Bytezuku/Shizuku before granting patch access."
                        )
                        return@execute
                    }

                    if (hasPatchAccess()) {
                        sendStatus(
                            true,
                            "Existing authorization detected. No additional permission request needed."
                        )
                        return@execute
                    }

                    runOnUiThread {
                        try {
                            Shizuku.requestPermission(
                                permissionRequestCode
                            )
                        } catch (e: Exception) {
                            sendStatus(
                                false,
                                "Permission request failed: ${e.message}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    sendStatus(
                        false,
                        "Authorization check failed: ${e.message}"
                    )
                }
            }
        }

        @JavascriptInterface
        fun startPatch() {
            executor.execute {
                try {
                    if (!Shizuku.pingBinder()) {
                        sendStatus(
                            false,
                            "Bytezuku/Shizuku is not running."
                        )
                        return@execute
                    }

                    if (!hasPatchAccess()) {
                        sendStatus(
                            false,
                            "Patch access is not authorized. Press Grant Patch Access first."
                        )
                        return@execute
                    }

                    sendStatus(
                        true,
                        "Connecting to the authorized Shizuku service..."
                    )

                    connectPatchService()

                } catch (e: Exception) {
                    sendStatus(
                        false,
                        "Unable to start patching: ${e.message}"
                    )
                }
            }
        }
    }

    private fun connectPatchService() {
        try {
            Shizuku.bindUserService(
                serviceArgs,
                patchServiceConnection
            )

            serviceBound = true

        } catch (e: Exception) {
            sendStatus(
                false,
                "Could not start the Shizuku UserService: ${e.message}"
            )
        }
    }

    private val patchServiceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                if (service == null) {
                    sendStatus(
                        false,
                        "The patch service returned no connection."
                    )
                    return
                }

                patchServiceBinder = service

                executor.execute {
                    executeObbCopy(service)
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {
                patchServiceBinder = null

                sendStatus(
                    false,
                    "The patch service disconnected."
                )
            }
        }

    private fun executeObbCopy(service: IBinder) {
        var data: Parcel? = null
        var reply: Parcel? = null

        try {
            sendStatus(
                true,
                "Copying OBB files..."
            )

            data = Parcel.obtain()
            reply = Parcel.obtain()

            data.writeInterfaceToken(serviceDescriptor)

            data.writeString(
                "/storage/emulated/0/Android/obb/com.BabTeam.Baboon"
            )

            data.writeString(
                "/storage/emulated/0/Android/obb/com.Tvmods.Baboon"
            )

            service.transact(
                serviceTransactionCode,
                data,
                reply,
                0
            )

            reply.readException()

            val result = reply.readString()
                ?: "The patch service returned no result."

            val success = result.startsWith("SUCCESS:")

            sendStatus(
                success,
                result.removePrefix("SUCCESS:")
                    .removePrefix("ERROR:")
            )

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
        Shizuku.removeRequestPermissionResultListener(
            permissionListener
        )

        if (serviceBound) {
            try {
                Shizuku.unbindUserService(
                    serviceArgs,
                    patchServiceConnection,
                    true
                )
            } catch (_: Exception) {
                // Service may already be disconnected.
            }
        }

        patchServiceBinder = null

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Patcher")
            webView.destroy()
        }

        executor.shutdownNow()

        super.onDestroy()
    }
}
