package dev.updateengine.hyperos.core.network

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.model.ProgressDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Downloads a recovery package into [destZipFile] with resumable transfers.
 *
 * A partial download is only ever resumed against a *validated* server state: the `.part` file is
 * paired with a `.part.meta` sidecar that records the strong validator (ETag/Last-Modified) and the
 * total object size seen when the partial was written. Without that sidecar the partial is treated as
 * unusable and the transfer restarts, because appending new bytes to bytes of an unknown revision
 * would silently produce a spliced file that only the later payload hash could catch.
 *
 * The transfer is also required to end exactly where the server said it would: a body that stops
 * early (proxy error page, dropped connection, chunked body of unknown length) is never renamed to
 * the final `.zip`.
 */
class RomDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    fun download(
        urls: List<String>,
        destZipFile: File
    ): Flow<ProgressDetail> = flow {
        val parent = destZipFile.parentFile
            ?: throw Exception("No destination directory for ${destZipFile.name}")

        val partFile = File(parent, destZipFile.name + ".part")
        val metaFile = File(parent, destZipFile.name + ".part.meta")

        var lastError: Exception? = null

        for (url in urls) {
            var resumeFrom = resumableBytes(partFile, metaFile)

            // Two attempts per mirror: the second one only happens when the first proved that the
            // partial we held is unusable (416, a 206 that does not continue our file, or an object
            // that changed), in which case the partial is discarded and the URL is retried from 0.
            for (attempt in 0..1) {
                var retrySameUrl = false
                try {
                    val meta = readMeta(metaFile)
                    val request = Request.Builder().url(url).apply {
                        if (resumeFrom > 0) {
                            header("Range", "bytes=$resumeFrom-")
                            meta?.validator?.let { header("If-Range", it) }
                        }
                    }.build()

                    val call = client.newCall(request)
                    val cancelHandler = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            call.cancel()
                        }
                    }
                    try {
                        call.execute().use { response ->
                            if (response.code == 416) {
                                // The object is shorter than what we hold. It is only "already complete"
                                // when the server's own total matches the partial byte for byte.
                                val total = contentRangeTotal(response.header("Content-Range"))
                                if (total != null && total > 0 && partFile.length() == total) {
                                    if (!partFile.renameTo(destZipFile)) {
                                        throw Exception("Failed to rename ${partFile.name} to ${destZipFile.name}")
                                    }
                                    discardMeta(metaFile)
                                    emit(ProgressDetail.Bytes(total, total, 0.0, 0L))
                                    return@flow
                                }
                                lastError = Exception("HTTP 416 from $url: the partial download does not match the server file")
                                discardPartial(partFile, metaFile)
                                resumeFrom = 0
                                retrySameUrl = true
                                return@use
                            }

                            if (!response.isSuccessful && response.code != 206) {
                                throw Exception("HTTP ${response.code} from $url")
                            }

                            val isRange = response.code == 206
                            if (!isRange && resumeFrom > 0) {
                                // The server ignored our Range (or re-uploaded the file): restart cleanly.
                                discardPartial(partFile, metaFile)
                                resumeFrom = 0
                            }

                            if (isRange) {
                                val start = contentRangeStart(response.header("Content-Range"))
                                val total = contentRangeTotal(response.header("Content-Range"))
                                val knownTotal = meta?.totalBytes ?: 0L
                                val continuesOurFile = start != null && start == resumeFrom
                                val sameObject = total == null || knownTotal <= 0L || total == knownTotal
                                if (!continuesOurFile || !sameObject) {
                                    lastError = Exception(
                                        "Resume rejected from $url: the server returned a range that does not " +
                                            "continue the partial download (start=$start, expected=$resumeFrom)."
                                    )
                                    discardPartial(partFile, metaFile)
                                    resumeFrom = 0
                                    retrySameUrl = true
                                    return@use
                                }
                            }

                            val body = response.body ?: throw Exception("Empty body from $url")
                            val contentLength = body.contentLength()
                            val rangeTotal = contentRangeTotal(response.header("Content-Range"))
                            val totalBytes = when {
                                isRange && rangeTotal != null -> rangeTotal
                                isRange && contentLength > 0 -> resumeFrom + contentLength
                                contentLength > 0 -> contentLength
                                else -> 0L
                            }

                            // Remember a strong validator before the transfer starts, so an interruption
                            // leaves a partial that can be resumed against the same revision.
                            writeMeta(metaFile, validatorOf(response), totalBytes)

                            val expectedBytes = if (isRange && contentLength > 0) {
                                resumeFrom + contentLength
                            } else {
                                contentLength
                            }

                            var bytesDownloaded = resumeFrom
                            RandomAccessFile(partFile, "rw").use { raf ->
                                if (resumeFrom > 0) raf.seek(resumeFrom) else raf.setLength(0)

                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    var lastReportTime = System.currentTimeMillis()
                                    var lastBytesReported = bytesDownloaded

                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        raf.write(buffer, 0, read)
                                        bytesDownloaded += read

                                        val now = System.currentTimeMillis()
                                        val timeDelta = now - lastReportTime
                                        if (timeDelta >= 500) {
                                            val bytesDelta = bytesDownloaded - lastBytesReported
                                            val rateBps = (bytesDelta.toDouble() / timeDelta) * 1000.0
                                            emitProgress(
                                                collector = this@flow,
                                                done = bytesDownloaded,
                                                total = totalBytes,
                                                rateBps = rateBps
                                            )
                                            lastReportTime = now
                                            lastBytesReported = bytesDownloaded
                                        }
                                    }
                                }
                            }

                            // A short transfer must never be blessed as a finished download: it would be
                            // renamed to the final .zip and then block every retry, because the next run
                            // sees a file that "already exists".
                            if (expectedBytes > 0 && bytesDownloaded != expectedBytes) {
                                discardPartial(partFile, metaFile)
                                resumeFrom = 0
                                throw Exception(
                                    "Download from $url ended early: $bytesDownloaded of $expectedBytes bytes."
                                )
                            }
                            if (bytesDownloaded <= 0L) {
                                discardPartial(partFile, metaFile)
                                throw Exception("Empty download from $url")
                            }

                            if (!partFile.renameTo(destZipFile)) {
                                throw Exception("Failed to rename ${partFile.name} to ${destZipFile.name}")
                            }
                            discardMeta(metaFile)
                            emit(ProgressDetail.Bytes(bytesDownloaded, bytesDownloaded, 0.0, 0L))
                            return@flow
                        }
                    } finally {
                        cancelHandler?.dispose()
                    }

                    if (retrySameUrl) continue
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        throw CancellationException("Download cancelled", e)
                    }
                    lastError = e
                    // Whatever the failure was, only the bytes a validated partial holds may be
                    // resumed; anything else starts over on the next mirror.
                    resumeFrom = resumableBytes(partFile, metaFile)
                    break
                }
            }
        }

        throw lastError ?: Exception("Failed to download from all mirror URLs")
    }.flowOn(dispatchers.io)

    private suspend fun emitProgress(
        collector: FlowCollector<ProgressDetail>,
        done: Long,
        total: Long,
        rateBps: Double
    ) {
        if (total <= 0L) {
            // A body of unknown length (chunked transfer) has no percentage to show.
            collector.emit(ProgressDetail.Indeterminate)
            return
        }
        val remainingBytes = (total - done).coerceAtLeast(0L)
        val etaSecs = if (rateBps > 0) (remainingBytes / rateBps).toLong() else null
        collector.emit(
            ProgressDetail.Bytes(done = done, total = total, rateBps = rateBps, etaSecs = etaSecs)
        )
    }

    internal companion object {
        private val CONTENT_RANGE = Regex("""bytes[ =](\d+)-(\d+)/(\d+|\*)""")
        private val UNSATISFIED_RANGE = Regex("""bytes[ =]\*/(\d+)""")
        private const val META_VALIDATOR = "validator="
        private const val META_TOTAL = "total="

        internal data class PartialMeta(val validator: String?, val totalBytes: Long)

        /** Strong validator only: a weak ETag is not a valid `If-Range` value. */
        internal fun validatorOf(response: Response): String? {
            val etag = response.header("ETag")?.trim()
            if (!etag.isNullOrBlank() && !etag.startsWith("W/")) return etag
            return response.header("Last-Modified")?.trim()?.takeIf { it.isNotBlank() }
        }

        internal fun contentRangeStart(header: String?): Long? =
            header?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

        internal fun contentRangeTotal(header: String?): Long? {
            if (header == null) return null
            UNSATISFIED_RANGE.find(header)?.let { match ->
                return match.groupValues[1].toLongOrNull()
            }
            val total = CONTENT_RANGE.find(header)?.groupValues?.get(3) ?: return null
            if (total == "*") return null
            return total.toLongOrNull()
        }

        internal fun readMeta(metaFile: File): PartialMeta? {
            if (!metaFile.isFile) return null
            return try {
                val lines = metaFile.readLines()
                val validator = lines.firstOrNull { it.startsWith(META_VALIDATOR) }
                    ?.removePrefix(META_VALIDATOR)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val total = lines.firstOrNull { it.startsWith(META_TOTAL) }
                    ?.removePrefix(META_TOTAL)
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L
                PartialMeta(validator, total)
            } catch (_: Exception) {
                null
            }
        }

        internal fun writeMeta(metaFile: File, validator: String?, totalBytes: Long) {
            try {
                metaFile.writeText(
                    buildString {
                        if (!validator.isNullOrBlank()) append("$META_VALIDATOR$validator\n")
                        append("$META_TOTAL$totalBytes\n")
                    }
                )
            } catch (_: Exception) {
                // A missing sidecar only costs the ability to resume; the download itself continues.
            }
        }

        internal fun discardMeta(metaFile: File) {
            try {
                metaFile.delete()
            } catch (_: Exception) {
            }
        }

        internal fun discardPartial(partFile: File, metaFile: File) {
            try {
                partFile.delete()
            } catch (_: Exception) {
            }
            discardMeta(metaFile)
        }

        /**
         * Bytes that may safely be resumed. Zero unless a `.part` and a matching sidecar agree: an
         * unvalidated partial could belong to a different revision of the same URL.
         */
        internal fun resumableBytes(partFile: File, metaFile: File): Long {
            if (!partFile.isFile) return 0L
            val length = partFile.length()
            if (length <= 0L) return 0L
            val meta = readMeta(metaFile) ?: return 0L
            if (meta.totalBytes in 1 until length) return 0L
            return length
        }
    }
}
