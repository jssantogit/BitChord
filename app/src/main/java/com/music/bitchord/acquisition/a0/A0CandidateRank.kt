package com.music.bitchord.acquisition.a0

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Pre-download *claims* classifier, never an audio verifier.
 * Match title in file, artist in file/folder, and inspect peer-advertised
 * duration, encoding, and size. The result is a hint for manual selection.
 * Do not log queries, names, paths, usernames, or ranking details.
 */
internal object A0CandidateRank {
    enum class Match { ALL_TERMS, SOME_TERMS, NO_TERMS }
    enum class Quality {
        LOSSLESS_ADVERTISED, HIGH_ADVERTISED, MEDIUM_ADVERTISED,
        LOW_ADVERTISED, UNKNOWN_ADVERTISED
    }
    enum class Warning {
        VERSION_MISMATCH, DURATION_MISMATCH, SIZE_SUSPICIOUS, ENCODING_CONFLICT,
        ARTIST_CONFLICT, IDENTITY_AMBIGUOUS
    }

    data class Ranking(
        val score:Int,
        val match:Match,
        val quality:Quality=Quality.UNKNOWN_ADVERTISED,
        val warnings:List<Warning> = emptyList()
    )
    private fun tokens(value:String):List<String> {
        val folded=Normalizer.normalize(value,Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
        return folded.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
    }

    private fun containsPhrase(haystack:List<String>,phrase:List<String>):Boolean=
        phrase.isNotEmpty() && haystack.windowed(phrase.size).any { it==phrase }

    /** Never allow peer-supplied bidi/zero-width formatting to spoof the UI. */
    private fun isUnsafeDisplayFormatChar(ch:Char)=
        ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' ||
            ch in '\u200B'..'\u200F' || ch=='\u2060' || ch=='\uFEFF'

    /** Display/safe local filename only; NEVER change remote P/F identity. */
    fun safeDisplayName(name:String):String=buildString(name.length) {
        name.forEach { append(if(isUnsafeDisplayFormatChar(it)) '_' else it) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun rank(
        searchPhrase:String?, name:String, declaredBitrateKbps:Int?,
        remotePath:String?=null, format:String?=null,
        declaredSampleRateHz:Int?=null, declaredBitDepth:Int?=null,
        declaredDurationSeconds:Int?=null, expectedDurationSeconds:Int?=null,
        sizeBytes:Long?=null, queueLength:Int?=null
    ):Ranking {
        val parts=searchPhrase.orEmpty().split(Regex("\\s+[-–—]\\s+"),limit=2)
        val titleSequence=tokens(if(parts.size==2)parts[1] else searchPhrase.orEmpty())
        val artistSequence=if(parts.size==2) tokens(parts[0]) else emptyList()
        val titleWords=titleSequence.distinct()
        val artistWords=artistSequence.distinct()
        val stem=name.substringBeforeLast('.',name)
        val fileTokens=tokens(stem)
        val fileWords=fileTokens.toSet()
        val parentSegments=remotePath.orEmpty().replace('\\','/')
            .split('/').dropLast(1)
        val folderWords=parentSegments.flatMap(::tokens).toSet()
        val artistInFilename=artistWords.isNotEmpty() && artistWords.all { it in fileWords }
        // Folder context is supporting evidence only when the *same* folder
        // segment identifies the artist. "New/Jeans" cannot imply "New Jeans".
        // A title can itself contain the artist's name ("Queen Of Hearts").
        // That is not independent credit. When names overlap, a supporting
        // folder must match the actual artist phrase, not the whole song title.
        val artistTitleOverlap=artistSequence.any { it in titleSequence }
        val artistInSingleFolder=artistWords.isNotEmpty() &&
            parentSegments.any { parent ->
                val words=tokens(parent)
                if(artistTitleOverlap) words==artistSequence
                else artistWords.all { it in words }
            }
        val titleMatches=titleWords.count { it in fileWords }
        val artistMatches=artistWords.count { it in fileWords || it in folderWords }
        val titleComplete=titleWords.isNotEmpty() && titleMatches==titleWords.size
        val artistComplete=artistWords.isEmpty() ||
            artistInFilename || artistInSingleFolder
        val match=when {
            titleComplete && artistComplete -> Match.ALL_TERMS
            titleMatches>0 || artistMatches>0 -> Match.SOME_TERMS
            else -> Match.NO_TERMS
        }
        val titleScore=when {
            titleComplete -> 52
            titleWords.isEmpty() -> 0
            else -> 32*titleMatches/titleWords.size
        }
        val artistScore=when {
            artistWords.isEmpty() -> 0
            artistComplete -> 22
            else -> 10*artistMatches/artistWords.size
        }
        val ext=(format ?: name.substringAfterLast('.', "")).lowercase(Locale.ROOT)
        val invalidMp3Bitrate=ext=="mp3" && declaredBitrateKbps!=null &&
            declaredBitrateKbps !in 32..320
        val validRate=declaredBitrateKbps?.takeIf {
            it in 32..(if(ext=="mp3") 320 else 1536)
        }
        val lossless=ext in setOf("flac","wav")
        val quality=when {
            lossless && declaredSampleRateHz?.let { it in 8000..384000 } == true &&
                declaredBitDepth?.let { it in 8..32 } == true -> Quality.LOSSLESS_ADVERTISED
            lossless -> Quality.UNKNOWN_ADVERTISED
            validRate!=null && validRate>=256 -> Quality.HIGH_ADVERTISED
            validRate!=null && validRate>=160 -> Quality.MEDIUM_ADVERTISED
            validRate!=null -> Quality.LOW_ADVERTISED
            else -> Quality.UNKNOWN_ADVERTISED
        }
        val qualityScore=when(quality) {
            Quality.LOSSLESS_ADVERTISED -> 18
            Quality.HIGH_ADVERTISED -> 13
            Quality.MEDIUM_ADVERTISED -> 6
            Quality.LOW_ADVERTISED -> -12
            Quality.UNKNOWN_ADVERTISED -> if (lossless) 6 else 0
        }
        // Existing manual handoffs without a query retain neutral ranking.
        // No user intent means no evidence to prefer a peer by advertised codec.
        var score=titleScore+artistScore+
            if(titleWords.isEmpty() && artistWords.isEmpty()) 0 else qualityScore
        // Different ambiguity signals can overlap; show one warning code
        // per cause in the manual selector and preflight diagnostics.
        val warnings=linkedSetOf<Warning>()
        val nameWords=fileWords
        // Unicode direction overrides and zero-width separators can make a
        // filename look like a different title/extension to the reader while
        // normal token matching still reports ALL_TERMS. Never classify that
        // display as STRONG on untrusted peer metadata.
        if(stem.any(::isUnsafeDisplayFormatChar)) {
            score-=9
            warnings.add(Warning.IDENTITY_AMBIGUOUS)
        }
        // Set inclusion and phrase order alone can silently accept both extra
        // copies ("ILLIT Magnetic Magnetic") and missing independent credit
        // ("Queen Of Hearts" for "Queen - Queen Of Hearts"). Compare the
        // multiplicity of expected artist/title words in the filename only.
        // A genuine exact artist directory can supply independent credit,
        // allowing a title-only file even when title and artist share words.
        if(titleSequence.isNotEmpty() && artistSequence.isNotEmpty()) {
            val observed=fileTokens.groupingBy { it }.eachCount()
            val titleOnly=titleSequence.groupingBy { it }.eachCount()
            val artistAndTitle=(artistSequence+titleSequence)
                .groupingBy { it }.eachCount()
            val relevant=(artistSequence+titleSequence).toSet()
            val actual=relevant.associateWith { observed[it] ?: 0 }
            val titleCounts=relevant.associateWith { titleOnly[it] ?: 0 }
            val combinedCounts=relevant.associateWith { artistAndTitle[it] ?: 0 }
            val complete=when {
                artistTitleOverlap && artistInSingleFolder ->
                    actual==titleCounts || actual==combinedCounts
                artistInFilename -> actual==combinedCounts
                else -> actual==titleCounts
            }
            if(!complete) {
                score-=9
                warnings.add(Warning.IDENTITY_AMBIGUOUS)
            }
        }
        // Explicit "Other Artist - Magnetic.mp3" contradicts the expected
        // credit even when an ancestor directory is called "ILLIT".
        // Numeric track prefixes ("01 - Magnetic") and title-led suffixes
        // ("Magnetic - Master") are not treated as artist claims.
        val stemParts=stem.split(Regex("\\s+[-–—]\\s+"),limit=2)
        if(artistWords.isNotEmpty() && stemParts.size==2) {
            val leading=tokens(stemParts[0]).toSet()
            val isTrackNumber=leading.isNotEmpty() &&
                leading.all { token -> token.all(Char::isDigit) }
            val isTitlePrefix=titleWords.isNotEmpty() &&
                titleWords.all { it in leading }
            val isExpectedCredit=artistWords.all { it in leading }
            if(leading.isNotEmpty() && !isTrackNumber &&
                !isTitlePrefix && !isExpectedCredit) {
                score-=36
                warnings.add(Warning.ARTIST_CONFLICT)
            }
        }
        // Extra filename words are not proof of a wrong file, but they can
        // conceal an uncredited performer, mix, reissue, or unrelated media.
        // Known presentation labels and track numbers are neutral; everything
        // else must be reviewed before any future unattended decision.
        if(titleWords.isNotEmpty() && artistWords.isNotEmpty()) {
            val expectedWords=(artistWords+titleWords).toSet()
            val neutralLabels=setOf(
                "official","audio","hq","hd","320","kbps","256",
                "cbr","vbr","track","stereo"
            )
            val unknown=fileWords.filter { token ->
                token !in expectedWords && token !in neutralLabels &&
                    !(token.length<=3 && token.all(Char::isDigit))
            }
            if(unknown.isNotEmpty()) {
                score-=7
                warnings.add(Warning.IDENTITY_AMBIGUOUS)
            }
        }
        // Token sets are useful for recall, but not sufficient evidence for
        // unattended selection: "Tears Your Save" is not "Save Your Tears",
        // and "Do.mp3" is not "Do Do Do". Preserve manual discovery, require
        // REVIEW when complete words occur in the wrong order/repetition.
        val titleOrdered=containsPhrase(fileTokens,titleSequence)
        val artistOrdered=artistSequence.isEmpty() ||
            containsPhrase(fileTokens,artistSequence) ||
            parentSegments.any { containsPhrase(tokens(it),artistSequence) }
        if(match==Match.ALL_TERMS && (!titleOrdered || !artistOrdered)) {
            score-=9
            if(Warning.IDENTITY_AMBIGUOUS !in warnings)
                warnings.add(Warning.IDENTITY_AMBIGUOUS)
        }
        val requestedWords=tokens(searchPhrase.orEmpty()).toSet()
        // A live/remix/acoustic recording is a mismatch only if that version
        // was NOT explicitly requested. Do not penalize a title called "Live".
        val immediateFolderWords=parentSegments.lastOrNull()
            ?.let { tokens(it).toSet() }.orEmpty()
        // A title-only filename can live inside an explicitly alternate
        // version album: "Magnetic (Live)/Magnetic.mp3". Inspect ONLY the
        // immediate folder to avoid a distant "Live Archive" ancestor
        // overriding normal album/artist context.
        if (setOf("live","remix","karaoke","instrumental","nightcore",
                "slowed","cover","sped","reverb","acoustic","demo",
                "extended","bootleg","8d","remaster","remastered",
                "radio","edit","rerecorded","re-recorded") .any {
                (it in nameWords || it in immediateFolderWords) &&
                    it !in requestedWords
            }) {
            score-=30
            warnings.add(Warning.VERSION_MISMATCH)
        }
        if (invalidMp3Bitrate) {
            score-=40
            warnings.add(Warning.ENCODING_CONFLICT)
        }
        if (declaredDurationSeconds!=null && expectedDurationSeconds!=null &&
            declaredDurationSeconds>0 && expectedDurationSeconds>0) {
            val delta=abs(declaredDurationSeconds.toLong()-expectedDurationSeconds.toLong())
            if (delta>25L) {
                score-=30
                warnings.add(Warning.DURATION_MISMATCH)
            } else if(delta>8L) {
                score-=10
                warnings.add(Warning.DURATION_MISMATCH)
            } else score+=8
        }
        if (validRate!=null && declaredDurationSeconds!=null &&
            declaredDurationSeconds in 30..14400 && sizeBytes!=null) {
            val predicted=validRate.toLong()*125L*declaredDurationSeconds
            if (predicted>0 && (sizeBytes<predicted*4L/10L || sizeBytes>predicted*22L/10L)) {
                score-=20
                warnings.add(Warning.SIZE_SUSPICIOUS)
            }
        }
        // The queue belongs to the source, not the recording. It must never
        // change the musical score. Keep queueLength in the signature for
        // compatibility with existing rank callers and baseline fixtures.
        return Ranking(score.coerceIn(0,100),match,quality,warnings.toList())
    }
}
