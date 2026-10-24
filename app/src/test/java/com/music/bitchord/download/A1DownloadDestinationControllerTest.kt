package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class A1DownloadDestinationControllerTest {
    private val selected="content://com.android.externalstorage.documents/tree/primary%3AMusic%2FChosen"

    private class Preference : A1DownloadDestinationController.Preference {
        var value:String?=null
        override fun read():String?=value
        override fun write(uri:String?):Boolean { value=uri; return true }
    }

    private class Grants : A1DownloadDestinationController.Grants {
        val granted=mutableSetOf<String>()
        var takeAllowed=true
        var takeCalls=0
        override fun takeReadWrite(uri:String):Boolean {
            takeCalls++
            if(!takeAllowed)return false
            granted+=uri
            return true
        }
        override fun hasReadWrite(uri:String):Boolean=uri in granted
    }

    @Test fun defaultIsSharedMusicAndNoPermissionPromptNeeded() {
        val pref=Preference()
        val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertEquals(
            A1DownloadDestinationPolicy.Resolution.Ready(
                A1DownloadDestinationPolicy.Target.SharedMusic("Music/BitChord/")
            ), controller.current()
        )
        assertEquals(0,grants.takeCalls)
    }

    @Test fun grantsBothPermissionsBeforeSavingAndSurvivesControllerRecreation() {
        val pref=Preference();val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertTrue(controller.choose(selected))
        assertEquals(selected,pref.value)
        assertEquals(
            A1DownloadDestinationPolicy.Resolution.Ready(
                A1DownloadDestinationPolicy.Target.GrantedTree(selected)
            ), A1DownloadDestinationController(pref,grants).current()
        )
    }

    @Test fun permissionDenialDoesNotOverwriteExistingPreference() {
        val pref=Preference();val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertTrue(controller.choose(selected))
        grants.takeAllowed=false
        val second="content://com.android.externalstorage.documents/tree/primary%3AMusic%2FOther"
        assertFalse(controller.choose(second))
        assertEquals(selected,pref.value)
    }

    @Test fun revokedPermissionMustNotSilentlyRedirectUserToPublicMusic() {
        val pref=Preference();val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertTrue(controller.choose(selected))
        grants.granted.clear()
        assertEquals(A1DownloadDestinationPolicy.Resolution.NeedsReselection,controller.current())
        assertEquals(selected,pref.value)
    }

    @Test fun rejectsUnsafeUriWithoutRequestingAnyPermission() {
        val pref=Preference();val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertFalse(controller.choose("file:///storage/emulated/0/Music"))
        assertEquals(0,grants.takeCalls)
        assertNull(pref.value)
    }

    @Test fun resetExplicitlyReturnsToMusicWithoutRevokingSharedOtherAppGrants() {
        val pref=Preference();val grants=Grants()
        val controller=A1DownloadDestinationController(pref,grants)
        assertTrue(controller.choose(selected))
        assertTrue(controller.reset())
        assertNull(pref.value)
        assertEquals(
            A1DownloadDestinationPolicy.Resolution.Ready(
                A1DownloadDestinationPolicy.Target.SharedMusic("Music/BitChord/")
            ),controller.current()
        )
        assertTrue(selected in grants.granted)
    }
}
