package com.example.batteryexpcollector

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class NetworkTaskStatsSnapshot(
    val activeWorkers: Int = 0,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val retryCount: Long = 0L,
    val operationCount: Long = 0L,
    val averageOperationDurationMs: Long = 0L
)

class NetworkLoadController {

    private val running = AtomicBoolean(false)
    private val activeWorkers = AtomicInteger(0)
    private val totalDownloadBytes = AtomicLong(0L)
    private val totalUploadBytes = AtomicLong(0L)
    private val successCount = AtomicLong(0L)
    private val failureCount = AtomicLong(0L)
    private val retryCount = AtomicLong(0L)
    private val operationCount = AtomicLong(0L)
    private val totalOperationDurationMs = AtomicLong(0L)

    @Volatile
    private var workers: List<Thread> = emptyList()

    fun start(config: CollectionConfig) {
        stop()
        if (!config.networkLoadEnabled) return

        resetStats()
        running.set(true)

        workers = List(config.networkConcurrency.coerceAtLeast(1)) { index ->
            Thread(
                {
                    runWorkerLoop(config)
                },
                "network-load-$index"
            ).apply {
                start()
            }
        }
    }

    fun stop() {
        running.set(false)
        workers.forEach { worker ->
            worker.interrupt()
            runCatching { worker.join(1_000L) }
        }
        workers = emptyList()
        activeWorkers.set(0)
    }

    fun snapshot(): NetworkTaskStatsSnapshot {
        val operations = operationCount.get()
        val averageDuration = if (operations > 0L) {
            totalOperationDurationMs.get() / operations
        } else {
            0L
        }

        return NetworkTaskStatsSnapshot(
            activeWorkers = activeWorkers.get(),
            totalDownloadBytes = totalDownloadBytes.get(),
            totalUploadBytes = totalUploadBytes.get(),
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            retryCount = retryCount.get(),
            operationCount = operations,
            averageOperationDurationMs = averageDuration
        )
    }

    private fun resetStats() {
        activeWorkers.set(0)
        totalDownloadBytes.set(0L)
        totalUploadBytes.set(0L)
        successCount.set(0L)
        failureCount.set(0L)
        retryCount.set(0L)
        operationCount.set(0L)
        totalOperationDurationMs.set(0L)
    }

    private fun runWorkerLoop(config: CollectionConfig) {
        activeWorkers.incrementAndGet()
        try {
            val uploadPayload = ByteArray(config.networkUploadChunkBytes).also {
                Random.Default.nextBytes(it)
            }

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val startMs = System.currentTimeMillis()
                try {
                    val result = executeOperationWithRetry(config, uploadPayload)
                    totalDownloadBytes.addAndGet(result.downloadBytes)
                    totalUploadBytes.addAndGet(result.uploadBytes)
                    successCount.incrementAndGet()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                    Log.w(TAG, "network operation failed scenario=${config.networkScenario}", e)
                } finally {
                    operationCount.incrementAndGet()
                    totalOperationDurationMs.addAndGet(
                        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                    )
                }

                if (config.networkScenario == NETWORK_SCENARIO_SMALL_REQUEST_BURST &&
                    running.get() &&
                    !Thread.currentThread().isInterrupted
                ) {
                    try {
                        Thread.sleep(config.networkBurstIntervalMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } finally {
            activeWorkers.decrementAndGet()
        }
    }

    private fun executeOperationWithRetry(
        config: CollectionConfig,
        uploadPayload: ByteArray
    ): TransferResult {
        val maxAttempts = if (config.networkRetryEnabled) {
            config.networkMaxRetryCount.coerceAtLeast(0) + 1
        } else {
            1
        }

        var lastError: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                return when (config.networkScenario) {
                    NETWORK_SCENARIO_DOWNLOAD_LOOP ->
                        executeDownload(config.networkDownloadUrl)

                    NETWORK_SCENARIO_UPLOAD_LOOP ->
                        executeUpload(config.networkUploadUrl, uploadPayload)

                    NETWORK_SCENARIO_SMALL_REQUEST_BURST ->
                        executeSmallRequest(config.networkBurstUrl)

                    else -> throw IllegalArgumentException("Unsupported network scenario")
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    retryCount.incrementAndGet()
                }
            }
        }

        throw lastError ?: IllegalStateException("Network operation failed without exception")
    }

    private fun executeDownload(urlString: String): TransferResult {
        val connection = openConnection(urlString, method = "GET", doOutput = false)
        return connection.useConnection {
            val responseCode = connection.responseCode
            ensureSuccess(responseCode)

            BufferedInputStream(connection.inputStream).use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read.toLong()
                }
                TransferResult(downloadBytes = total)
            }
        }
    }

    private fun executeUpload(urlString: String, payload: ByteArray): TransferResult {
        val connection = openConnection(urlString, method = "POST", doOutput = true)
        return connection.useConnection {
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")

            BufferedOutputStream(connection.outputStream).use { stream ->
                stream.write(payload)
                stream.flush()
            }

            val responseCode = connection.responseCode
            ensureSuccess(responseCode)

            val responseBytes = readResponseBodyBytes(connection)
            TransferResult(
                downloadBytes = responseBytes,
                uploadBytes = payload.size.toLong()
            )
        }
    }

    private fun executeSmallRequest(urlString: String): TransferResult {
        val connection = openConnection(urlString, method = "GET", doOutput = false)
        return connection.useConnection {
            val responseCode = connection.responseCode
            ensureSuccess(responseCode)

            TransferResult(downloadBytes = readResponseBodyBytes(connection))
        }
    }

    private fun openConnection(
        urlString: String,
        method: String,
        doOutput: Boolean
    ): HttpURLConnection {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            this.doOutput = doOutput
            this.doInput = true
        }
        return connection
    }

    private fun readResponseBodyBytes(connection: HttpURLConnection): Long {
        val inputStream = try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream
        } ?: return 0L

        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read.toLong()
            }
            return total
        }
    }

    private fun ensureSuccess(responseCode: Int) {
        if (responseCode !in 200..299) {
            throw IOException("Unexpected HTTP response code: $responseCode")
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: () -> T): T {
        try {
            connect()
            return block()
        } finally {
            disconnect()
        }
    }

    private data class TransferResult(
        val downloadBytes: Long = 0L,
        val uploadBytes: Long = 0L
    )

    companion object {
        private const val TAG = "NetworkLoadController"
        private const val BUFFER_SIZE = 8_192
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
