package com.music.bitchord.acquisition.a0

import java.text.Normalizer

/**
 * Narrow width-only equivalence for CJK filenames, titles and album claims.
 *
 * NFKC on an entire Unicode string is NOT safe for unattended selection:
 * compatibility ideographs can collapse different Chinese/Japanese text.
 * Compose NFC and fold only fullwidth ASCII / halfwidth Japanese kana.
 * Do not translate simplified/traditional Han, hiragana/katakana, or romanize.
 */
internal object A0CjkOrthography {
    // NFC canonically decomposes even some CJK *compatibility* ideographs
    // (U+F900..FAFF and U+2F800..2FA1F). Preserve their exact code points
    // rather than equating visually similar but distinctly catalogued Han.
    // Normalize only the segments around them; Hangul Jamo composition and
    // Latin canonical accent equivalence remain intact within each segment.
    private fun nfcExceptCompatibilityIdeographs(value:String):String {
        val result=StringBuilder(value.length)
        var segmentStart=0
        var cursor=0
        while(cursor<value.length) {
            val cp=Character.codePointAt(value,cursor)
            val next=cursor+Character.charCount(cp)
            if(cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F) {
                if(segmentStart<cursor)
                    result.append(Normalizer.normalize(
                        value.substring(segmentStart,cursor),Normalizer.Form.NFC))
                result.appendCodePoint(cp)
                segmentStart=next
            }
            cursor=next
        }
        if(segmentStart<value.length)
            result.append(Normalizer.normalize(
                value.substring(segmentStart),Normalizer.Form.NFC))
        return result.toString()
    }

    fun widthOnlyNfc(value:String):String {
        val canonical=nfcExceptCompatibilityIdeographs(value)
        if(canonical.none { it in '\uFF01'..'\uFF5E' ||
                it in '\uFF61'..'\uFF9F' })return canonical
        val widthFolded=buildString(canonical.length) {
            canonical.forEach { character ->
                if(character in '\uFF01'..'\uFF5E' ||
                    character in '\uFF61'..'\uFF9F')
                    append(Normalizer.normalize(
                        character.toString(),Normalizer.Form.NFKC))
                else append(character)
            }
        }
        // Halfwidth dakuten converts to a combining mark; compose it with
        // the preceding kana only AFTER the width-only substitution.
        return nfcExceptCompatibilityIdeographs(widthFolded)
    }
}
