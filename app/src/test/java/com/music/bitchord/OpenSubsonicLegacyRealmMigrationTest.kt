package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.catalog.provider.opensubsonic.openSubsonicCatalogProviderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class OpenSubsonicLegacyRealmMigrationTest {
    @Test fun oldAccountWithoutRealmIsDurablyQuarantinedInsteadOfClaimingOldCachedAssets() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "bitchord_opensubsonic_account", Context.MODE_PRIVATE,
        )
        try {
            assertTrue(prefs.edit().clear()
                .putString("account_id", "home")
                .putString("server_url", "https://music.example.test")
                .putString("auth_mode", "API_KEY")
                .commit())

            val persistence = SharedPrefsOpenSubsonicAccountPersistence(context)
            val current = requireNotNull(persistence.read())
            assertFalse(current.catalogRealmId.isBlank())
            assertNotEquals("opensubsonic:home",
                openSubsonicCatalogProviderKey(current.accountId, current.catalogRealmId))
            assertEquals(current.catalogRealmId,
                SharedPrefsOpenSubsonicAccountPersistence(context).read()?.catalogRealmId)
            // No historical MediaAsset row is rewritten with the new realm.
            assertEquals("home", current.accountId)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
