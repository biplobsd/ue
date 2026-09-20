package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.ota.EngineLogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogParserTest {

    @Test
    fun testParseStatusDownloading() {
        val line = "[INFO:update_engine_client_android.cc(96)] onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.5123)"
        val status = EngineLogParser.parseStatusLine(line)
        assertNotNull(status)
        assertEquals("DOWNLOADING", status!!.statusName)
        assertEquals(3, status.statusCode)
        assertEquals(0.5123f, status.progress, 0.0001f)
    }

    @Test
    fun testParseStatusNeedReboot() {
        val line = "[INFO:update_engine_client_android.cc(96)] onStatusUpdate(UPDATE_STATUS_UPDATED_NEED_REBOOT (6), 0)"
        val status = EngineLogParser.parseStatusLine(line)
        assertNotNull(status)
        assertEquals("UPDATED_NEED_REBOOT", status!!.statusName)
        assertEquals(6, status.statusCode)
        assertEquals(0f, status.progress, 0.0001f)
    }

    @Test
    fun testParsePayloadCompleteSuccess() {
        val line = "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kSuccess (0))"
        val complete = EngineLogParser.parseCompletionLine(line)
        assertNotNull(complete)
        assertEquals("kSuccess", complete!!.errorName)
        assertEquals(0, complete.errorCode)
        assertTrue(complete.isSuccess)
    }

    @Test
    fun testParsePayloadCompleteTimestampError() {
        val line = "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kPayloadTimestampError (51))"
        val complete = EngineLogParser.parseCompletionLine(line)
        assertNotNull(complete)
        assertEquals("kPayloadTimestampError", complete!!.errorName)
        assertEquals(51, complete.errorCode)
        assertFalse(complete.isSuccess)
        assertTrue(complete.userExplanation.contains("downgrade rejected"))
    }

    @Test
    fun testUpdatedButNotActiveCountsAsSuccess() {
        // The client exits with EX_OK for kUpdatedButNotActive, so reporting it as a failure would
        // stop the pipeline on a payload that was actually applied.
        val line = "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kUpdatedButNotActive (52))"
        val complete = EngineLogParser.parseCompletionLine(line)!!

        assertTrue(complete.isSuccess)
        assertTrue(complete.userExplanation.contains("applied"))
    }

    @Test
    fun testNotEnoughSpaceAndRollbackCodesAreExplained() {
        val outOfSpace = EngineLogParser.parseCompletionLine(
            "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kNotEnoughSpace (60))"
        )!!
        assertFalse(outOfSpace.isSuccess)
        assertTrue(outOfSpace.userExplanation.contains("free space"))

        // 54 is kRollbackNotPossible, not a partition mismatch.
        val rollback = EngineLogParser.parseCompletionLine(
            "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kRollbackNotPossible (54))"
        )!!
        assertTrue(rollback.userExplanation.contains("roll"))
    }

    @Test
    fun testUnknownCodeIsStillReported() {
        val complete = EngineLogParser.parseCompletionLine(
            "[INFO:update_engine_client_android.cc(104)] onPayloadApplicationComplete(ErrorCode::kSomethingElse (123))"
        )!!
        assertFalse(complete.isSuccess)
        assertEquals(123, complete.errorCode)
    }
}
