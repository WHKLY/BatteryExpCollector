package com.example.batteryexpcollector

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStreamWriter

object SharedResultsStore {

    private const val APP_RESULTS_DIR_NAME = "BatteryExpCollector"
    private val DOWNLOADS_RELATIVE_PATH =
        "${Environment.DIRECTORY_DOWNLOADS}/$APP_RESULTS_DIR_NAME"

    const val RESULTS_DIRECTORY_LABEL: String = "Download/$APP_RESULTS_DIR_NAME"

    data class StoredTextFile(
        val uri: Uri,
        val displayName: String,
        val logicalPath: String
    )

    fun createTextFile(
        context: Context,
        displayName: String,
        mimeType: String = "text/plain"
    ): StoredTextFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_RELATIVE_PATH)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("Failed to create MediaStore file: $displayName")

            StoredTextFile(
                uri = uri,
                displayName = displayName,
                logicalPath = "$RESULTS_DIRECTORY_LABEL/$displayName"
            )
        } else {
            val dir = File(context.getExternalFilesDir(null), APP_RESULTS_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, displayName)
            if (!file.exists()) {
                file.createNewFile()
            }

            StoredTextFile(
                uri = Uri.fromFile(file),
                displayName = displayName,
                logicalPath = file.absolutePath
            )
        }
    }

    fun appendText(context: Context, uri: Uri, text: String) {
        writeTextInternal(context, uri, text, append = true)
    }

    fun overwriteText(context: Context, uri: Uri, text: String) {
        writeTextInternal(context, uri, text, append = false)
    }

    fun readLines(context: Context, uri: Uri): List<String> {
        return if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "file uri path is null" })
            if (!file.exists()) emptyList() else file.readLines()
        } else {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
                ?: emptyList()
        }
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        return if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "file uri path is null" })
            if (file.exists()) file.length() else 0L
        } else {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        }
    }

    private fun writeTextInternal(
        context: Context,
        uri: Uri,
        text: String,
        append: Boolean
    ) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "file uri path is null" })
            if (append) file.appendText(text) else file.writeText(text)
            return
        }

        val mode = if (append) "wa" else "wt"
        val outputStream = context.contentResolver.openOutputStream(uri, mode)
            ?: throw FileNotFoundException("Unable to open output stream for $uri")

        outputStream.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.write(text)
                writer.flush()
            }
        }
    }
}
