package com.example.batteryexpcollector.automation.orchestrator

import android.content.Context
import com.example.batteryexpcollector.SharedResultsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExecutionLogger(
    context: Context,
    experimentId: String
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val storedFile: SharedResultsStore.StoredTextFile

    init {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        storedFile = SharedResultsStore.createTextFile(
            context = appContext,
            displayName = "${timestamp}_${sanitize(experimentId)}_execution.log",
            mimeType = "text/plain"
        )

        info("Execution logger initialized")
        info("log_file=${storedFile.logicalPath}")
        info("results_directory=${SharedResultsStore.RESULTS_DIRECTORY_LABEL}")
    }

    fun info(message: String) = write("INFO", message)

    fun warn(message: String) = write("WARN", message)

    fun error(message: String) = write("ERROR", message)

    fun getLogicalPath(): String = storedFile.logicalPath

    private fun write(level: String, message: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        SharedResultsStore.appendText(
            appContext,
            storedFile.uri,
            "[$now][$level] $message\n"
        )
    }

    private fun sanitize(input: String): String {
        return input.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
    }

    override fun close() {
        // no-op
    }
}