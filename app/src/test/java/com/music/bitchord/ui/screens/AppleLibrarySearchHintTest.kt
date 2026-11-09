package com.music.bitchord.ui.screens

import android.app.Application
import android.content.res.Configuration
import com.music.bitchord.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Library category hints must follow Android locale without changing search behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleLibrarySearchHintTest {
    private fun localized(locale: Locale, title: String): String {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocale(locale)
        return app.createConfigurationContext(config)
            .getString(R.string.apple_library_search_in_category, title)
    }

    @Test fun categoriesUseLocalizedSearchHintInEnglish() {
        assertEquals("Search in Artists", localized(Locale.ENGLISH, "Artists"))
        assertEquals("Search in Albums", localized(Locale.ENGLISH, "Albums"))
        assertEquals("Search in Songs", localized(Locale.ENGLISH, "Songs"))
    }

    @Test fun playlistsAndCategoriesShareLocalizedHintInPortuguese() {
        val locale = Locale.forLanguageTag("pt-BR")
        assertEquals("Buscar em Playlists", localized(locale, "Playlists"))
        assertEquals("Buscar em Artistas", localized(locale, "Artistas"))
    }
}
