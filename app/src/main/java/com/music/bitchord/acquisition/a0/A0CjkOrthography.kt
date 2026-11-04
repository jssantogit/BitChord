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
    fun widthOnlyNfc(value:String):String {
        val canonical=Normalizer.normalize(value,Normalizer.Form.NFC)
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
        return Normalizer.normalize(widthFolded,Normalizer.Form.NFC)
    }
}
