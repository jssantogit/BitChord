package com.music.bitchord.playback

import androidx.media3.common.PlaybackException
import com.music.bitchord.data.model.QueueTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureAutoAdvancePolicyTest {

    @Test
    fun `canonical remote network open failure auto advances instead of using legacy resolver`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            neverStarted = true,
        )

        assertEquals(PlaybackFailureDisposition.AUTO_ADVANCE, disposition)
        assertNotEquals(PlaybackFailureDisposition.LEGACY_RECOVERY, disposition)
    }

    @Test
    fun `canonical remote bad http status covers forgotten or invalid account without legacy fallback`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            neverStarted = true,
        )

        assertEquals(PlaybackFailureDisposition.AUTO_ADVANCE, disposition)
        assertNotEquals(PlaybackFailureDisposition.LEGACY_RECOVERY, disposition)
    }

    @Test
    fun `canonical remote internal decoder failure stays terminal rather than invoking legacy resolver`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            neverStarted = true,
        )

        assertEquals(PlaybackFailureDisposition.TERMINAL, disposition)
        assertNotEquals(PlaybackFailureDisposition.LEGACY_RECOVERY, disposition)
    }

    @Test
    fun `canonical remote failure after audio started stays terminal for existing interrupted playback semantics`() {
        val disposition = classifyPlaybackFailure(
            canonicalRemote = true,
            localUriScheme = "bitchord",
            errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            neverStarted = false,
        )

        assertEquals(PlaybackFailureDisposition.TERMINAL, disposition)
    }

    @Test
    fun `stale content local asset participates in auto advance`() {
        assertEquals(
            PlaybackFailureDisposition.AUTO_ADVANCE,
            classifyPlaybackFailure(
                canonicalRemote = false,
                localUriScheme = "content",
                errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                neverStarted = true,
            ),
        )
    }

    @Test
    fun `stale file local asset participates in auto advance`() {
        assertEquals(
            PlaybackFailureDisposition.AUTO_ADVANCE,
            classifyPlaybackFailure(
                canonicalRemote = false,
                localUriScheme = "file",
                errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                neverStarted = true,
            ),
        )
    }

    @Test
    fun `unrelated legacy network failure remains owned by legacy recovery`() {
        assertEquals(
            PlaybackFailureDisposition.LEGACY_RECOVERY,
            classifyPlaybackFailure(
                canonicalRemote = false,
                localUriScheme = "https",
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                neverStarted = true,
            ),
        )
    }

    @Test
    fun `context failure identity ignores repeat cycle and physical queue occurrence`() {
        val firstCycle = failureEntryKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 7,
            queueEntryId = "physical-cycle-one",
            physicalIndex = 2,
        )
        val laterCycle = failureEntryKey(
            tier = QueueTier.CONTEXT,
            canonicalIndex = 7,
            queueEntryId = "physical-cycle-two",
            physicalIndex = 19,
        )

        assertEquals("context:7", firstCycle)
        assertEquals(firstCycle, laterCycle)
    }

    @Test
    fun `user queue failure identity keeps distinct queue entries distinct`() {
        val first = failureEntryKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            queueEntryId = "queued-copy-a",
            physicalIndex = 1,
        )
        val second = failureEntryKey(
            tier = QueueTier.USER_QUEUE,
            canonicalIndex = null,
            queueEntryId = "queued-copy-b",
            physicalIndex = 2,
        )

        assertEquals("entry:queued-copy-a", first)
        assertEquals("entry:queued-copy-b", second)
        assertNotEquals(first, second)
    }

    @Test
    fun `fallback failure identity contains no transport locator`() {
        val key = failureEntryKey(
            tier = QueueTier.AUTOPLAY,
            canonicalIndex = null,
            queueEntryId = null,
            physicalIndex = 4,
        )

        assertEquals("slot:4", key)
        assertFalse(key.contains("http"))
        assertFalse(key.contains("content://"))
    }

    @Test
    fun `diagnostics boundary contains only safe media3 error code`() {
        val line = safePlaybackFailureDiagnostic(
            errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            outcome = PlaybackFailureDiagnosticOutcome.ADVANCE,
            failedCount = 1,
        )

        assertTrue(line.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        listOf(
            "password",
            "sekrit",
            "stream.view",
            "Authorization",
            "https://",
            "sourceKey",
        ).forEach { forbidden -> assertFalse(line.contains(forbidden)) }
    }

    @Test
    fun `exhausted diagnostic exposes only bounded count and safe error code`() {
        val line = safePlaybackFailureDiagnostic(
            errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            outcome = PlaybackFailureDiagnosticOutcome.EXHAUSTED,
            failedCount = 3,
        )

        assertTrue(line.contains("3 queue entries"))
        assertTrue(line.contains("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
        assertFalse(line.contains("token"))
        assertFalse(line.contains("stream.view"))
        assertFalse(line.contains("http"))
    }
}
