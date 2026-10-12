package com.Tvmods.BaboonPatcher

import android.os.Binder
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
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int
    ): Boolean {

        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }

        if (code != TRANSACTION_COPY_OBB) {
            return super.onTransact(code, data, reply, flags)
        }

        data.enforceInterface(DESCRIPTOR)

        val sourcePath = data.readString()
        val destinationPath = data.readString()

        try {
            require(
                sourcePath ==
                    "/storage/emulated/0/Android/obb/$SOURCE_PACKAGE"
            ) {
                "Unexpected source directory."
            }

            require(
                destinationPath ==
                    "/storage/emulated/0/Android/obb/$DESTINATION_PACKAGE"
            ) {
                "Unexpected destination directory."
            }

            val source = File(sourcePath)
            val destination = File(destinationPath)

            if (!source.isDirectory) {
                throw IllegalStateException(
                    "Source OBB directory does not exist: $sourcePath"
                )
            }

            val files = source.listFiles()
                ?: throw IllegalStateException(
                    "Cannot read the source OBB directory."
                )

            val obbFiles = files.filter { it.isFile }

            if (obbFiles.isEmpty()) {
                throw IllegalStateException(
                    "No OBB files were found in the source directory."
                )
            }

            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException(
                    "Could not create the destination directory."
                )
            }

            var copied = 0

            for (file in obbFiles) {
                val newName = file.name.replace(
                    SOURCE_PACKAGE,
                    DESTINATION_PACKAGE
                )

                val target = File(destination, newName)

                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }

                copied++
            }

            reply?.writeNoException()
            reply?.writeString(
                "SUCCESS:Copied $copied OBB file(s) to $destinationPath"
            )

        } catch (e: Exception) {
            reply?.writeNoException()
            reply?.writeString(
                "ERROR:${e.message ?: "Unknown file-copy error"}"
            )
        }

        return true
    }
}
