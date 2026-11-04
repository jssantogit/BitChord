package com.music.bitchord.acquisition.a0

/**
 * Read-only canonical-artist spelling bridge. A true bilingual artist credit
 * can be written either as "BTS (방탄소년단)" or "방탄소년단 (BTS)".
 *
 * Do NOT discard parentheses in general: "(feat. X)", "(Live)" and two
 * distinct Latin names are never aliases. The short Latin name may be used
 * only as an EXACT filename prefix or complete directory credit; a title
 * containing those words is not independent evidence of the performer.
 * Neither a canonical name nor a peer's label verifies an edition or audio.
 */
internal object A0ArtistCreditAliases {
    private val explicit=Regex("^\\s*([^()]+?)\\s*\\(([^()]+)\\)\\s*$")
    private val whitespace=Regex("\\s+")

    private fun latinWords(value:String):Boolean {
        if(value.length !in 2..48)return false
        val words=value.trim().split(whitespace)
        return words.size in 1..4 && words.all { word ->
            word.length>=2 && word.all { ch ->
                ch.isLetterOrDigit() &&
                    Character.UnicodeScript.of(ch.code)==
                        Character.UnicodeScript.LATIN
            }
        }
    }

    private fun nativeWords(value:String):Boolean {
        if(value.length !in 2..48)return false
        val words=value.trim().split(whitespace)
        return words.size in 1..4 && words.all { word ->
            word.any(Char::isLetter) && word.all { ch ->
                when {
                    ch.isLetter() ->
                        Character.UnicodeScript.of(ch.code)!=
                            Character.UnicodeScript.LATIN
                    else -> Character.getType(ch)==Character.NON_SPACING_MARK.toInt() ||
                        Character.getType(ch)==Character.COMBINING_SPACING_MARK.toInt()
                }
            }
        }
    }

    /** An exact Latin alias, sourced only from the requested canonical artist. */
    fun latinAlias(canonicalArtist:String):String? {
        val normalized=java.text.Normalizer.normalize(
            canonicalArtist,java.text.Normalizer.Form.NFC)
        val match=explicit.matchEntire(normalized) ?: return null
        val first=match.groupValues[1].trim()
        val second=match.groupValues[2].trim()
        return when {
            latinWords(first) && nativeWords(second) -> first
            nativeWords(first) && latinWords(second) -> second
            else -> null
        }
    }
}
