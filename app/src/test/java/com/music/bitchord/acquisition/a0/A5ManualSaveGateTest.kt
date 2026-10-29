package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test

class A5ManualSaveGateTest {
    @Test fun onlyExplicitlySupportedAudioExtensionsEligibleForSave() {
        assertEquals("Live Song.flac",A5ManualSaveGate.safeDestinationName("Live Song.flac",1024))
        assertEquals("Track.MP3",A5ManualSaveGate.safeDestinationName("Track.MP3",1024))
        assertEquals("sample.wav",A5ManualSaveGate.safeDestinationName("sample.wav",1024))
        assertEquals("radio.aac",A5ManualSaveGate.safeDestinationName("radio.aac",1024))
        assertEquals("sound.ogg",A5ManualSaveGate.safeDestinationName("sound.ogg",1024))
        assertEquals("song.opus",A5ManualSaveGate.safeDestinationName("song.opus",1024))
        assertNull(A5ManualSaveGate.safeDestinationName("song.wma",1024))
        assertNull(A5ManualSaveGate.safeDestinationName("song.m4a",1024))
    }
    @Test fun untrustedPathsAndControlCharactersNeverBecomeDeviceNames() {
        for (name in listOf("../song.mp3","folder\\song.mp3",
            "/storage/song.wav","artist/song.wav","a:b.mp3",".","..",
            "song.mp3.part","hello\n.mp3","bad" + 0.toChar() + ".mp3"
        )) {
            assertNull(name,A5ManualSaveGate.safeDestinationName(name,1024))
        }
    }
    @Test fun enforceObservedPilotSizeAndRequireUserSaveConsent() {
        assertNull(A5ManualSaveGate.safeDestinationName("sample.wav",0))
        assertNull(A5ManualSaveGate.safeDestinationName("sample.wav",43))
        assertNull(A5ManualSaveGate.safeDestinationName("sample.wav",256L*1024*1024+1))
        assertEquals("sample.wav",
            A5ManualSaveGate.safeDestinationName("sample.wav",256L*1024*1024))
        assertEquals("hires.flac",
            A5ManualSaveGate.safeDestinationName("hires.flac",96L*1024*1024))
        assertFalse(A5ManualSaveGate.canSave(true,false))
        assertFalse(A5ManualSaveGate.canSave(false,true))
        assertTrue(A5ManualSaveGate.canSave(true,true))
    }
}
