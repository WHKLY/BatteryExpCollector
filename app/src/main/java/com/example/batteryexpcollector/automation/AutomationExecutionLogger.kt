package com.example.batteryexpcollector.automation

import android.content.Context
import com.example.batteryexpcollector.SharedResultsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutomationExecutionLogger(
    context: Context,
    experimentId: String
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val storedFile = SharedResultsStore.createTextFile(
        context = appContext,
        displayName = buildFileName(experimentId),
        mimeType = "text/plain"
    )

    init {
        info("Automation execution logger initialized")
        info("log_file=${storedFile.logicalPath}")
        info("results_directory=${SharedResultsStore.RESULTS_DIRECTORY_LABEL}")
    }

    fun info(message: String) = write("INFO", message)

    fun warn(message: String) = write("WARN", message)

    fun error(message: String) = write("ERROR", message)

    fun logicalPath(): String = storedFile.logicalPath

    private fun buildFileName(experimentId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitized = experimentId.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return "${ts}_${sanitized}_local_execution.log"
    }

    private fun write(level: String, message: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        SharedResultsStore.appendText(
            appContext,
            storedFile.uri,
            "[$now][$level] $message\n"
        )
    }

    override fun close() {
        // no-op
    }
}