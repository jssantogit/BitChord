package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.ui.components.CompactRatingStyle
import com.music.bitchord.auth.AuthStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompactRatingStyleSettingsTest {
    @Test fun choiceWritesToExistingPreferencesAndSurvivesReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("compact_rating_style_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val field = AppSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousPrefs = field.get(AppSettings)
        val authField = AppSettings::class.java.getDeclaredField("authStore")
            .apply { isAccessible = true }
        val previousAuth = authField.get(AppSettings)
        val previousStyle = AppSettings.compactRatingStyle.value
        try {
            field.set(AppSettings, prefs)
            authField.set(AppSettings, AuthStore(context))
            AppSettings.setCompactRatingStyle(CompactRatingStyle.NUMERIC)
            assertEquals(CompactRatingStyle.NUMERIC, AppSettings.compactRatingStyle.value)
            assertEquals("NUMERIC", prefs.getString("compact_rating_style", null))

            // Simulates reloading after a process restart without any music reload.
            AppSettings.compactRatingStyle.value = CompactRatingStyle.COLOR
            AppSettings.reload()
            assertEquals(CompactRatingStyle.NUMERIC, AppSettings.compactRatingStyle.value)

            prefs.edit().putString("compact_rating_style", "CORRUPTED").commit()
            AppSettings.reload()
            assertEquals(CompactRatingStyle.COLOR, AppSettings.compactRatingStyle.value)
        } finally {
            field.set(AppSettings, previousPrefs)
            authField.set(AppSettings, previousAuth)
            AppSettings.compactRatingStyle.value = previousStyle
            prefs.edit().clear().commit()
        }
    }
}
