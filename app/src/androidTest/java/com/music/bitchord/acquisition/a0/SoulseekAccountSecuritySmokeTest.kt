package com.music.bitchord.acquisition.a0

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** On-device regression: no duplicate launcher, account secrets protected, logout fail closed. */
@RunWith(AndroidJUnit4::class)
class SoulseekAccountSecuritySmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oneNormalAppLauncherAndNoExportedA0Activity() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(launcher, 0)
        assertEquals(1, activities.size)
        assertFalse(activities.single().activityInfo.name.contains("A0ManualLoginActivity"))
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(context.packageName,
                "com.music.bitchord.acquisition.a0.A0ManualLoginActivity"), 0
        )
        assertFalse(activity.exported)
    }

    @Test fun accountSurvivesNewVaultInstanceAndNeverWritesPlaintext() {
        val vault = SoulseekCredentialVault(context)
        vault.forget()
        try {
            val password = "fixture-secret-never-log".toCharArray()
            vault.save("fixture-account-A", password)
            password.fill('\u0000')
            val saved = SoulseekCredentialVault(context).read()
            assertNotNull(saved)
            assertEquals("fixture-account-A", saved!!.username)
            assertArrayEquals("fixture-secret-never-log".toCharArray(), saved.password)
            saved.erase()
            val file = File(context.noBackupFilesDir, "soulseek-account-v1.bin")
            assertTrue(file.exists())
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1)
                .contains("fixture-secret-never-log"))
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1)
                .contains("fixture-account-A"))
        } finally { vault.forget() }
    }

    @Test fun logoutClosesAnyInFlightAuthenticatedSocket() {
        val vault = SoulseekCredentialVault(context)
        vault.forget()
        val socket = java.net.Socket()
        try {
            assertTrue(SoulseekActiveSession.register(
                socket, SoulseekActiveSession.generation()
            ))
            vault.forget()
            assertTrue(socket.isClosed)
            assertFalse(SoulseekActiveSession.register(socket,
                SoulseekActiveSession.generation() - 1))
        } finally {
            socket.close()
            vault.forget()
        }
    }

    @Test fun switchingAccountsReplacesSecretAndLogoutRevokes() {
        val vault = SoulseekCredentialVault(context)
        vault.forget()
        try {
            val old = "old-exclusive-secret".toCharArray()
            vault.save("account-A", old)
            old.fill('\u0000')
            val replacement = "new-exclusive-secret".toCharArray()
            vault.save("account-B", replacement)
            replacement.fill('\u0000')
            val stored = vault.read()
            assertNotNull(stored)
            assertEquals("account-B", stored!!.username)
            assertArrayEquals("new-exclusive-secret".toCharArray(), stored.password)
            stored.erase()
            vault.forget()
            assertNull(SoulseekCredentialVault(context).read())
            assertFalse(File(context.noBackupFilesDir, "soulseek-account-v1.bin").exists())
        } finally { vault.forget() }
    }
}
