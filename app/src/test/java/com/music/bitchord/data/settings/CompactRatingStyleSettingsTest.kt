package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.ui.components.CompactRatingStyle
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
        val previousStyle = AppSettings.compactRatingStyle.value
        try {
            field.set(AppSettings, prefs)
            AppSettings.setCompactRatingStyle(CompactRatingStyle.NUMERIC)
            assertEquals(CompactRatingStyle.NUMERIC, AppSettings.compactRatingStyle.value)
            assertEquals("NUMERIC", prefs.getString("compact_rating_style", null))

            // The same decoder used by AppSettings.readAll on every cold launch.
            // No encrypted auth/keystore is involved in this plain visual setting.
            AppSettings.compactRatingStyle.value = CompactRatingStyle.COLOR
            assertEquals(
                CompactRatingStyle.NUMERIC,
                compactRatingStyleFromPersisted(prefs.getString("compact_rating_style", null)),
            )
            prefs.edit().putString("compact_rating_style", "CORRUPTED").commit()
            assertEquals(
                CompactRatingStyle.COLOR,
                compactRatingStyleFromPersisted(prefs.getString("compact_rating_style", null)),
            )
            assertEquals(CompactRatingStyle.COLOR, compactRatingStyleFromPersisted(null))
        } finally {
            field.set(AppSettings, previousPrefs)
            AppSettings.compactRatingStyle.value = previousStyle
            prefs.edit().clear().commit()
        }
    }
}
