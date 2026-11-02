package com.music.bitchord.acquisition.a0

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.download.SoulseekBatchPlan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces the physical-phone bug: 23 pending tracks but only Voltar shown. */
@RunWith(AndroidJUnit4::class)
class A0PendingReviewChooserDialogSmokeTest {
    @Test
    fun pendingTwentyThreeShowsTwentyThreeClickableRowsAndMapsOriginalIndex() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=Intent(context,A0ManualLoginActivity::class.java)
        ActivityScenario.launch<A0ManualLoginActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val entries=List(25) { index ->
                    SoulseekBatchPlan.Entry("slot-$index",
                        "Artist - Track $index","Album",180)
                }
                // Slot 0 and slot 13 are already saved, thus NOT selectable.
                val pending=(0 until entries.size).filterNot { it==0 || it==13 }
                var selected:Int?=null
                val dialog=A0PendingReviewChooserDialog.create(
                    activity,pending,entries) { selected=it }
                try {
                    dialog.show()
                    val list=checkNotNull(dialog.listView)
                    assertTrue("Pending list must be VISIBLE, not replaced by a message",
                        list.isShown)
                    assertEquals(23,list.count)
                    assertTrue(list.getItemAtPosition(0).toString().contains("Track 1"))
                    list.performItemClick(null,12,12L)
                    assertEquals(14,selected)
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }
}
