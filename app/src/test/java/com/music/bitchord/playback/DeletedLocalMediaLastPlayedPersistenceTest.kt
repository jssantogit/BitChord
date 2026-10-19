package com.music.bitchord.playback

import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the same LastPlayed codec and synchronous disk-boundary write the
 * service uses after pruning, with a reopened reader and no fake Media3 state.
 */
class DeletedLocalMediaLastPlayedPersistenceTest {
    @Test
    fun `cold restart prunes deleted current but preserves persisted queue and entry identity`() {
        val prefs = FakePrefs()
        LastPlayed.initForTesting(prefs)
        val deleted = local("deleted", 42L).copy(queueEntryId = "deleted-entry")
        val next = local("next", 43L).copy(queueEntryId = "next-entry")
        val after = local("after", 44L).copy(queueEntryId = "after-entry")
        LastPlayed.saveQueueImmediately(listOf(deleted, next, after), 0, 24_500L)

        // First process launches after MediaStore has removed row 42.
        val cold = requireNotNull(LastPlayed.load())
        val filtered = requireNotNull(DeletedLocalMediaRestore.filter(cold) {
            it.localUri == deleted.localUri
        })
        LastPlayed.saveQueueImmediately(filtered.songs, filtered.index, filtered.positionMs)

        // Simulate a second launch from the same durable bytes.
        LastPlayed.initForTesting(prefs)
        val reopened = requireNotNull(LastPlayed.load())
        assertEquals(listOf("next", "after"), reopened.songs.map { it.videoId })
        assertEquals(listOf("next-entry", "after-entry"), reopened.songs.map { it.queueEntryId })
        assertEquals(0, reopened.index)
        assertEquals(0L, reopened.positionMs)
    }

    @Test
    fun `all deleted local items remove persisted last played queue without ghost state`() {
        val prefs = FakePrefs()
        LastPlayed.initForTesting(prefs)
        val stale = local("stale", 80L)
        LastPlayed.saveQueueImmediately(listOf(stale), 0, 12_000L)

        val filtered = DeletedLocalMediaRestore.filter(requireNotNull(LastPlayed.load())) { true }
        assertNull(filtered)
        LastPlayed.clearImmediately()

        LastPlayed.initForTesting(prefs)
        assertNull(LastPlayed.load())
    }

    private fun local(id: String, mediaId: Long) = Song(
        videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null,
        localUri = "content://media/external/audio/media/$mediaId",
    )

    private class FakePrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val writes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor { if (key != null) writes[key] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) removals += key; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) prefs.values.clear()
                removals.forEach(prefs.values::remove)
                writes.forEach { (key, value) ->
                    if (value == null) prefs.values.remove(key) else prefs.values[key] = value
                }
            }
        }
    }
}
