package com.music.bitchord

import com.music.bitchord.data.scrobbling.ListenBrainzOutboxStore
import com.music.bitchord.data.scrobbling.PendingListenBrainzListen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListenBrainzOutboxStoreTest {
    private fun listen(id: String, account: String = "accountA") = PendingListenBrainzListen(
        occurrenceId = id,
        accountKey = account,
        startMs = 1_780_000_000_000L,
        endMs = 1_780_000_070_000L,
        listenedMs = 70_000L,
        durationMs = 120_000L,
        artist = "BTS",
        title = "봄날",
        album = "You Never Walk Alone",
    )

    @Test fun durableRestartRetainsPayloadAndAvoidsDuplicateOccurrence() {
        val dir = Files.createTempDirectory("listenbrainz-outbox").toFile()
        try {
            val file = File(dir, "pending.json")
            val first = ListenBrainzOutboxStore(file)
            assertTrue(first.enqueue(listen("repeat-1")))
            assertFalse(first.enqueue(listen("repeat-1")))
            assertEquals(listOf("repeat-1"), ListenBrainzOutboxStore(file).pending("accountA").map { it.occurrenceId })
            assertFalse(file.readText().contains("Token "))
            assertTrue(file.readText().contains("봄날"))
            assertTrue(ListenBrainzOutboxStore(file).remove("repeat-1", "accountA"))
            assertTrue(ListenBrainzOutboxStore(file).pending("accountA").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun differentAccountIsNeverDrainedAndCanBePurged() {
        val dir = Files.createTempDirectory("listenbrainz-accounts").toFile()
        try {
            val store = ListenBrainzOutboxStore(File(dir, "pending.json"))
            store.enqueue(listen("old", "accountA"))
            store.enqueue(listen("new", "accountB"))
            assertEquals(listOf("new"), store.pending("accountB").map { it.occurrenceId })
            assertFalse(store.remove("old", "accountB"))
            store.keepOnlyAccount("accountB")
            assertEquals(0, store.pending("accountA").size)
            assertEquals(1, store.pending("accountB").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun acknowledgedOccurrenceCannotReappearAfterWorkerRestart() {
        val dir = Files.createTempDirectory("listenbrainz-ack").toFile()
        try {
            val file = File(dir, "pending.json")
            val store = ListenBrainzOutboxStore(file)
            assertTrue(store.enqueue(listen("played-once")))
            assertTrue(store.remove("played-once", "accountA"))
            val restarted = ListenBrainzOutboxStore(file)
            assertFalse(restarted.enqueue(listen("played-once")))
            assertTrue(restarted.pending("accountA").isEmpty())
            assertTrue(restarted.enqueue(listen("different-occurrence")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun acknowledgedHistoryIsRevokedWhenAccountChanges() {
        val dir = Files.createTempDirectory("listenbrainz-revocation").toFile()
        try {
            val store = ListenBrainzOutboxStore(File(dir, "pending.json"))
            assertTrue(store.enqueue(listen("same-id", "accountA")))
            assertTrue(store.remove("same-id", "accountA"))
            store.keepOnlyAccount("accountB")
            assertTrue(store.enqueue(listen("same-id", "accountB")))
            store.clear()
            assertTrue(store.enqueue(listen("same-id", "accountA")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun shortSkipAndMalformedMetadataCannotBeQueued() {
        assertFalse(listen("short").copy(listenedMs = 5_000L).isQualified())
        assertFalse(listen("bad-title").copy(title = " ").isQualified())
        assertFalse(listen("bad-time").copy(endMs = 1L).isQualified())
        assertTrue(listen("qualified").isQualified())
    }
}
