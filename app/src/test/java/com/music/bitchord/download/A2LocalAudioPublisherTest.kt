package com.music.bitchord.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class A2LocalAudioPublisherTest {
    @get:Rule val temp=TemporaryFolder()

    private fun wav():ByteArray = ByteArray(128).also { bytes ->
        "RIFF".toByteArray().copyInto(bytes,0)
        "WAVE".toByteArray().copyInto(bytes,8)
        "fmt ".toByteArray().copyInto(bytes,12)
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun file(bytes:ByteArray):File=temp.newFile().apply { writeBytes(bytes) }

    private class FakeSink: A2LocalAudioPublisher.PendingDestination {
        val bytes=ByteArrayOutputStream()
        var published=false
        var aborted=false
        var failAfterBytes=false
        var uri="content://media/external/audio/media/42"
        override fun openStream():OutputStream = if(!failAfterBytes) bytes else
            object:OutputStream() {
                override fun write(b:Int) { throw IOException("sink failed") }
                override fun write(b:ByteArray,off:Int,len:Int) { throw IOException("sink failed") }
            }
        override fun publish():String { published=true;return uri }
        override fun abort() { aborted=true }
    }

    private class RecordingFactory(
        val sink:FakeSink=FakeSink()
    ):A2LocalAudioPublisher.DestinationFactory {
        var opened=0
        var lastName:String?=null
        var lastMime:String?=null
        override fun createPending(name:String,mime:String):A2LocalAudioPublisher.PendingDestination {
            opened++
            lastName=name
            lastMime=mime
            return sink
        }
    }

    @Test fun verifiedWavPublishesOnlyAfterBytesAndDigestMatch() {
        val bytes=wav()
        val factory=RecordingFactory()
        val result=A2LocalAudioPublisher.publish(
            file(bytes),"Sample.wav",bytes.size.toLong(),sha(bytes),factory)
        assertEquals("content://media/external/audio/media/42",result.contentUri)
        assertEquals("audio/x-wav",result.mimeType)
        assertEquals("Sample.wav",factory.lastName)
        assertArrayEquals(bytes,factory.sink.bytes.toByteArray())
        assertTrue(factory.sink.published)
        assertFalse(factory.sink.aborted)
    }

    @Test fun cannotPublishWhenObservedReceiptHashDoesNotMatch() {
        val bytes=wav()
        val factory=RecordingFactory()
        try {
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",bytes.size.toLong(),
                "0".repeat(64),factory)
            fail("Mismatch should abort")
        }catch(_:IOException){}
        assertFalse(factory.sink.published)
        assertTrue(factory.sink.aborted)
    }

    @Test fun badSizeCannotReserveAnyUserVisibleFile() {
        val bytes=wav();val factory=RecordingFactory()
        try {
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",bytes.size+1L,
                sha(bytes),factory)
            fail("Wrong size must fail")
        }catch(_:IOException){}
        assertEquals(0,factory.opened)
    }

    @Test fun disguisedAudioIsNotPublished() {
        val bytes=ByteArray(128){0x45}
        val factory=RecordingFactory()
        try {
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",bytes.size.toLong(),
                sha(bytes),factory)
            fail("Invalid header must fail")
        }catch(_:IOException){}
        assertEquals(0,factory.opened)
    }

    @Test fun remotePathsAndFilenameTraversalRejectedBeforeOpeningDestination() {
        for(name in listOf("../evil.wav","subdir/song.wav","subdir\\song.wav",
            ".", "..", "song.wav.part", "hello\n.wav")) {
            val bytes=wav();val factory=RecordingFactory()
            try {
                A2LocalAudioPublisher.publish(file(bytes),name,bytes.size.toLong(),
                    sha(bytes),factory)
                fail("Filename should be rejected")
            }catch(_:IllegalArgumentException){}
            assertEquals(name,0,factory.opened)
        }
    }

    @Test fun mismatchedExtensionAndAudioHeaderNeverReservesDestination() {
        val bytes=wav();val factory=RecordingFactory()
        try {
            A2LocalAudioPublisher.publish(file(bytes),"not-wav.mp3",
                bytes.size.toLong(),sha(bytes),factory)
            fail("Container and extension mismatch must be rejected")
        }catch(_:IOException){}
        assertEquals(0,factory.opened)
    }

    @Test fun cancellationBeforePublicationAbortsPendingFile() {
        val bytes=wav()
        val factory=RecordingFactory()
        var polls=0
        try {
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",
                bytes.size.toLong(),sha(bytes),factory) { ++polls>=2 }
            fail("Cancellation should abort pending output")
        }catch(_:IOException){}
        assertTrue(factory.sink.aborted)
        assertFalse(factory.sink.published)
    }

    @Test fun interruptedOutputIsAbortedAndNeverPublished() {
        val bytes=wav();val factory=RecordingFactory(FakeSink().apply{failAfterBytes=true})
        try {
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",bytes.size.toLong(),
                sha(bytes),factory)
            fail("Failed copy should abort")
        }catch(_:IOException){}
        assertTrue(factory.sink.aborted)
        assertFalse(factory.sink.published)
    }

    @Test fun destinationCollisionIsNotSilentlyOverwritten() {
        val bytes=wav()
        val factory=object:A2LocalAudioPublisher.DestinationFactory {
            override fun createPending(name:String,mime:String):
                A2LocalAudioPublisher.PendingDestination =
                    throw IOException("destination already exists")
        }
        try{
            A2LocalAudioPublisher.publish(file(bytes),"Sample.wav",bytes.size.toLong(),
                sha(bytes),factory)
            fail("Collision must fail")
        }catch(_:IOException){}
    }
    @Test fun realAdtsAacHeaderMayPublishAsAacButNeverAsM4a() {
        val bytes=ByteArray(128).also { out ->
            // MPEG-4 AAC-LC, 44.1 kHz, stereo, ADTS frame length 128.
            byteArrayOf(0xff.toByte(),0xf1.toByte(),0x50,0x80.toByte(),
                0x10,0x1f,0xfc.toByte()).copyInto(out)
        }
        val factory=RecordingFactory()
        val saved=A2LocalAudioPublisher.publish(
            file(bytes),"Sample.aac",bytes.size.toLong(),sha(bytes),factory)
        assertEquals("audio/aac",saved.mimeType)
        assertTrue(factory.sink.published)
        val otherFactory=RecordingFactory()
        try {
            A2LocalAudioPublisher.publish(
                file(bytes),"Sample.m4a",bytes.size.toLong(),sha(bytes),otherFactory)
            fail("ADTS is not an ISO-BMFF M4A container")
        } catch(_:IOException) {}
        assertEquals(0,otherFactory.opened)
    }

}
