package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalNormalPlaylistPickerTest {
    @Test fun togglingAddsAndRemovesCanonicalRecordingIdsWithoutChangingOtherOrder() {
        val initial = listOf("recording-one", "recording-two")
        val added = toggleCanonicalStaticMember(initial, "recording-three")
        assertEquals(listOf("recording-one", "recording-two", "recording-three"), added)
        val removed = toggleCanonicalStaticMember(added, "recording-two")
        assertEquals(listOf("recording-one", "recording-three"), removed)
        assertEquals(initial, toggleCanonicalStaticMember(removed, "recording-two")
            .let { listOf(it[0], it[2]) })
    }

    @Test fun reorderPreservesAllCanonicalMembersAndHonoursBoundaries() {
        val initial = listOf("rec-a", "rec-b", "rec-c")
        assertEquals(listOf("rec-b", "rec-a", "rec-c"),
            moveCanonicalStaticMember(initial, "rec-b", towardStart = true))
        assertEquals(listOf("rec-a", "rec-c", "rec-b"),
            moveCanonicalStaticMember(initial, "rec-b", towardStart = false))
        assertEquals(initial, moveCanonicalStaticMember(initial, "rec-a", towardStart = true))
        assertEquals(initial, moveCanonicalStaticMember(initial, "rec-c", towardStart = false))
        assertEquals(initial, moveCanonicalStaticMember(initial, "absent", towardStart = true))
    }

    @Test fun noDuplicateRecordingIsIntroducedByRepeatedSelection() {
        val first = toggleCanonicalStaticMember(emptyList(), "rec-a")
        val second = toggleCanonicalStaticMember(first, "rec-a")
        val third = toggleCanonicalStaticMember(second, "rec-a")
        assertEquals(listOf("rec-a"), third)
    }
}
