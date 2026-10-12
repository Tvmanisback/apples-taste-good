package com.Tvmods.BaboonPatcher

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PatchUserService : Binder() {

    companion object {
        private const val DESCRIPTOR =
            "com.Tvmods.BaboonPatcher.PatchUserService"

        private const val TRANSACTION_COPY_OBB = 1

        private const val SOURCE_PACKAGE =
            "com.BabTeam.Baboon"

        private const val DESTINATION_PACKAGE =
            "com.Tvmods.Baboon"

        private const val SOURCE_PATH =
            "/storage/emulated/0/Android/obb/com.BabTeam.Baboon"

        private const val DESTINATION_PATH =
            "/storage/emulated/0/Android/obb/com.Tvmods.Baboon"
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int
    ): Boolean {

        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }

        if (code != TRANSACTION_COPY_OBB) {
            return super.onTransact(code, data, reply, flags)
        }

        data.enforceInterface(DESCRIPTOR)

        val requestedSource = data.readString()
        val requestedDestination = data.readString()

        try {
            require(requestedSource == SOURCE_PATH) {
                "Unexpected source directory."
            }

            require(requestedDestination == DESTINATION_PATH) {
                "Unexpected destination directory."
            }

            val source = File(SOURCE_PATH)
            val destination = File(DESTINATION_PATH)

            if (!source.isDirectory) {
                throw IllegalStateException(
                    "Source OBB directory was not found: $SOURCE_PATH"
                )
            }

            val files = source.listFiles()
                ?: throw IllegalStateException(
                    "Cannot list the source OBB directory. Check shell access."
                )

            val obbFiles = files.filter { it.isFile }

            if (obbFiles.isEmpty()) {
                throw IllegalStateException(
                    "No files were found in the source OBB directory."
                )
            }

            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException(
                    "Cannot create the destination OBB directory: $DESTINATION_PATH"
                )
            }

            if (!destination.isDirectory || !destination.canWrite()) {
                throw IllegalStateException(
                    "The destination OBB directory is not writable."
                )
            }

            var copied = 0
            var totalBytes = 0L

            for (file in obbFiles) {
                val renamed = file.name.replace(
                    SOURCE_PACKAGE,
                    DESTINATION_PACKAGE
                )

                val target = File(destination, renamed)

                if (target.canonicalFile.parentFile != destination.canonicalFile) {
                    throw IllegalStateException(
                        "Invalid destination filename."
                    )
                }

                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output ->
                        val bytesCopied = input.copyTo(output)
                        output.fd.sync()
                        totalBytes += bytesCopied
                    }
                }

                copied++
            }

            reply?.writeNoException()
            reply?.writeString(
                "SUCCESS:Copied $copied OBB file(s) ($totalBytes bytes) to $DESTINATION_PATH"
            )

        } catch (e: Exception) {
            reply?.writeNoException()
            reply?.writeString(
                "ERROR:${e.message ?: "Unknown OBB copy error"}"
            )
        }

        return true
    }
}
