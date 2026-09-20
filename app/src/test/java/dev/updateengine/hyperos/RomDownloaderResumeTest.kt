package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.network.RomDownloader
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The resume rules decide whether bytes already on disk may be kept. Getting them wrong means
 * appending a new revision of a file onto an old one, so they are asserted directly.
 */
class RomDownloaderResumeTest {

    private fun response(vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://example.invalid/ota.zip").build())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .body("".toResponseBody(null))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun tempDir(): File = File.createTempFile("downloader", "").let { file ->
        file.delete()
        file.mkdirs()
        file.deleteOnExit()
        file
    }

    @Test
    fun testContentRangeIsParsedForPartialResponses() {
        assertEquals(1000L, RomDownloader.contentRangeStart("bytes 1000-4999/5000"))
        assertEquals(5000L, RomDownloader.contentRangeTotal("bytes 1000-4999/5000"))
        assertNull(RomDownloader.contentRangeStart("bytes */5000"))
        assertNull(RomDownloader.contentRangeTotal("bytes 1000-4999/*"))
        assertNull(RomDownloader.contentRangeStart(null))
    }

    @Test
    fun testUnsatisfiedRangeStillReportsTheObjectSize() {
        assertEquals(5000L, RomDownloader.contentRangeTotal("bytes */5000"))
    }

    @Test
    fun testWeakValidatorsAreNotUsedForIfRange() {
        assertNull(RomDownloader.validatorOf(response("ETag" to "W/\"abc\"")))
        assertEquals("\"abc\"", RomDownloader.validatorOf(response("ETag" to "\"abc\"")))
        assertEquals(
            "Wed, 21 Oct 2026 07:28:00 GMT",
            RomDownloader.validatorOf(response("Last-Modified" to "Wed, 21 Oct 2026 07:28:00 GMT"))
        )
    }

    @Test
    fun testPartialWithoutSidecarIsNeverResumed() {
        val dir = tempDir()
        val part = File(dir, "ota.zip.part").apply { writeBytes(ByteArray(4096)) }
        val meta = File(dir, "ota.zip.part.meta")

        assertEquals(0L, RomDownloader.resumableBytes(part, meta))
    }

    @Test
    fun testValidatedPartialIsResumed() {
        val dir = tempDir()
        val part = File(dir, "ota.zip.part").apply { writeBytes(ByteArray(4096)) }
        val meta = File(dir, "ota.zip.part.meta")
        RomDownloader.writeMeta(meta, "\"etag\"", 10_000L)

        assertEquals(4096L, RomDownloader.resumableBytes(part, meta))
        assertEquals(10_000L, RomDownloader.readMeta(meta)?.totalBytes)
        assertEquals("\"etag\"", RomDownloader.readMeta(meta)?.validator)
    }

    @Test
    fun testPartialLongerThanTheRecordedObjectIsRejected() {
        val dir = tempDir()
        val part = File(dir, "ota.zip.part").apply { writeBytes(ByteArray(4096)) }
        val meta = File(dir, "ota.zip.part.meta")
        RomDownloader.writeMeta(meta, "\"etag\"", 1024L)

        assertEquals(0L, RomDownloader.resumableBytes(part, meta))
    }

    @Test
    fun testDiscardingRemovesBothThePartialAndItsSidecar() {
        val dir = tempDir()
        val part = File(dir, "ota.zip.part").apply { writeBytes(ByteArray(16)) }
        val meta = File(dir, "ota.zip.part.meta")
        RomDownloader.writeMeta(meta, "\"etag\"", 16L)

        RomDownloader.discardPartial(part, meta)

        assertEquals(0L, RomDownloader.resumableBytes(part, meta))
        assertEquals(false, part.exists())
        assertEquals(false, meta.exists())
    }
}
