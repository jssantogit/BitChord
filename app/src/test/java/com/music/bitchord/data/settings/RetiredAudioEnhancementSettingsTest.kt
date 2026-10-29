package com.music.bitchord.data.settings

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredAudioEnhancementSettingsTest {
    @Test
    fun `old call sites cannot reenable retired audio effects or persist their flags`() {
        val field = AppSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousPrefs = field.get(AppSettings)
        val previousStates = listOf(
            AppSettings.dolbyAtmos.value,
            AppSettings.spatialAudio.value,
            AppSettings.equalizerEnabled.value,
            AppSettings.loudnessNormalization.value,
        )
        val removedKeys = mutableListOf<String>()
        val writtenKeys = mutableListOf<String>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "remove" -> {
                    removedKeys += args!![0] as String
                    proxy
                }
                "putBoolean" -> {
                    writtenKeys += args!![0] as String
                    proxy
                }
                "apply" -> null
                else -> null
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            if (method.name == "edit") editor else null
        } as SharedPreferences
        try {
            field.set(AppSettings, prefs)
            AppSettings.dolbyAtmos.value = true
            AppSettings.spatialAudio.value = true
            AppSettings.equalizerEnabled.value = true
            AppSettings.loudnessNormalization.value = true

            AppSettings.setDolbyAtmos(true)
            AppSettings.setSpatialAudio(true)
            AppSettings.setEqualizerEnabled(true)
            AppSettings.setLoudnessNormalization(true)

            assertFalse(AppSettings.dolbyAtmos.value)
            assertFalse(AppSettings.spatialAudio.value)
            assertFalse(AppSettings.equalizerEnabled.value)
            assertFalse(AppSettings.loudnessNormalization.value)
            assertTrue(writtenKeys.isEmpty())
            assertEquals(
                setOf("dolby_atmos", "spatial_audio", "equalizer_enabled", "loudness_normalization"),
                removedKeys.toSet(),
            )
        } finally {
            field.set(AppSettings, previousPrefs)
            AppSettings.dolbyAtmos.value = previousStates[0]
            AppSettings.spatialAudio.value = previousStates[1]
            AppSettings.equalizerEnabled.value = previousStates[2]
            AppSettings.loudnessNormalization.value = previousStates[3]
        }
    }
}
