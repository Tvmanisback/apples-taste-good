@JavascriptInterface
fun startPatch() {
executor.execute {
try {
if (!Shizuku.pingBinder()) {
sendStatus(“ERROR: Shizuku is not running.”)
return@execute
}

        if (Shizuku.checkSelfPermission() !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            sendStatus("ERROR: Shizuku permission is not granted.")
            return@execute
        }
        sendStatus("Checking access...")
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
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", script),
            null,
            null
        )
        val stdout = process.inputStream.bufferedReader().use {
            it.readText()
        }
        val stderr = process.errorStream.bufferedReader().use {
            it.readText()
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
        sendStatus("ERROR: ${e.message ?: "Command execution failed."}")
    }
}

}
