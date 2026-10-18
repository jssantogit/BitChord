package com.music.bitchord

import com.music.bitchord.data.catalog.identity.IsrcNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsrcNormalizationTest {

    @Test
    fun uppercaseCanonicalIsrcRemainsStable() {
        assertEquals("USS1Z9900001", IsrcNormalizer.normalize("USS1Z9900001"))
    }

    @Test
    fun lowercaseAndPresentationHyphensNormalizeToCanonicalUppercase() {
        assertEquals("USS1Z9900001", IsrcNormalizer.normalize("us-s1z-99-00001"))
        assertEquals("GBABC2400001", IsrcNormalizer.normalize("gb-abc-24-00001"))
    }

    @Test
    fun invalidValuesAreRejectedRatherThanRepaired() {
        assertNull(IsrcNormalizer.normalize("not-an-isrc"))
        assertNull(IsrcNormalizer.normalize("US-S1Z-XX-00001"))
        assertNull(IsrcNormalizer.normalize("1S-S1Z-99-00001"))
        assertNull(IsrcNormalizer.normalize("USS1Z990001"))
        assertNull(IsrcNormalizer.normalize("US S1Z 99 00001"))
    }

    @Test
    fun normalizedValuesAreDeduplicatedDeterministically() {
        assertEquals(
            listOf("USS1Z9900001", "GBABC2400001"),
            IsrcNormalizer.normalizeDistinct(
                listOf(
                    "US-S1Z-99-00001",
                    "uss1z9900001",
                    "garbage",
                    "GB-ABC-24-00001",
                    "gbabc2400001",
                ),
            ),
        )
    }
}
