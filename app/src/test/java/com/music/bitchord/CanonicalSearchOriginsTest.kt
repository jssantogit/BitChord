package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.SearchOrigin
import com.music.bitchord.ui.searchOriginOf
import com.music.bitchord.ui.searchSourceForOrigin
import com.music.bitchord.data.catalog.isCatalogRemoteServerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSearchOriginsTest {
    @Test fun oneGeneralOriginHidesMetadataProviderSplit() {
        assertEquals(SearchOrigin.GENERAL, searchOriginOf(SearchSource.YOUTUBE))
        assertEquals(SearchOrigin.GENERAL, searchOriginOf(SearchSource.CANONICAL))
        assertEquals(SearchOrigin.SERVER, searchOriginOf(SearchSource.ADDONS))
        assertEquals(SearchOrigin.LOCAL, searchOriginOf(SearchSource.LIBRARY))
    }
    @Test fun generalUsesCatalogWithCanonicalModeOrYoutubeOptOut() {
        val on = listOf(SourceConfig(kind=SourceKind.YOUTUBE, enabled=true))
        val off = listOf(SourceConfig(kind=SourceKind.YOUTUBE, enabled=false))
        assertEquals(SearchSource.YOUTUBE,searchSourceForOrigin(SearchOrigin.GENERAL,false,on))
        assertEquals(SearchSource.CANONICAL,searchSourceForOrigin(SearchOrigin.GENERAL,true,on))
        assertEquals(SearchSource.CANONICAL,searchSourceForOrigin(SearchOrigin.GENERAL,false,off))
        assertEquals(SearchSource.ADDONS,searchSourceForOrigin(SearchOrigin.SERVER,true,off))
        assertEquals(SearchSource.LIBRARY,searchSourceForOrigin(SearchOrigin.LOCAL,true,off))
    }
    @Test fun serverSearchOnlyAcceptsTrustworthyProviderNamespaceNotLocalOrMusicBrainz() {
        assertTrue(isCatalogRemoteServerProvider("opensubsonic:configured-account"))
        assertTrue(isCatalogRemoteServerProvider("jellyfin:configured-account"))
        assertFalse(isCatalogRemoteServerProvider("local"))
        assertFalse(isCatalogRemoteServerProvider("musicbrainz"))
        assertFalse(isCatalogRemoteServerProvider("opensubsonic"))
    }
}
