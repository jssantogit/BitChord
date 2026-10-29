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

    @Test fun noDuplicateRecordingIsIntroducedByRepeatedSelection() {
        val first = toggleCanonicalStaticMember(emptyList(), "rec-a")
        val second = toggleCanonicalStaticMember(first, "rec-a")
        val third = toggleCanonicalStaticMember(second, "rec-a")
        assertEquals(listOf("rec-a"), third)
    }
}
