package com.music.bitchord.data.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.ui.components.CompactRatingStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompactRatingStyleSettingsTest {
    @Test fun choiceWritesToExistingPreferencesAndSurvivesReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("compact_rating_style_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val field = AppSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousPrefs = field.get(AppSettings)
        val previousStyle = AppSettings.compactRatingStyle.value
        try {
            field.set(AppSettings, prefs)
            AppSettings.setCompactRatingStyle(CompactRatingStyle.NUMERIC)
            assertEquals(CompactRatingStyle.NUMERIC, AppSettings.compactRatingStyle.value)
            assertEquals("NUMERIC", prefs.getString("compact_rating_style", null))

            // The same hydration path used by AppSettings.readAll on cold launch.
            AppSettings.compactRatingStyle.value = CompactRatingStyle.COLOR
            AppSettings.refreshCompactRatingStyleFromPreferences()
            assertEquals(CompactRatingStyle.NUMERIC, AppSettings.compactRatingStyle.value)

            prefs.edit().putString("compact_rating_style", "CORRUPTED").commit()
            AppSettings.refreshCompactRatingStyleFromPreferences()
            assertEquals(CompactRatingStyle.COLOR, AppSettings.compactRatingStyle.value)
            assertEquals(CompactRatingStyle.COLOR, compactRatingStyleFromPersisted(null))
        } finally {
            field.set(AppSettings, previousPrefs)
            AppSettings.compactRatingStyle.value = previousStyle
            prefs.edit().clear().commit()
        }
    }
}
