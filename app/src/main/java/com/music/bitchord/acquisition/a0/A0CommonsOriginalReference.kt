package com.music.bitchord.acquisition.a0

/**
 * A0 immutable independently reviewed ORIGINAL reference, not peer metadata.
 *
 * Source: https://commons.wikimedia.org/wiki/File:Ringingsound.wav
 * Uploader/creator: Kaga Sound, source Own work, CC0-1.0 license declaration.
 *
 * The exact original file, not a transcode, was fetched and independently
 * hashed in a restricted one-shot read-only GitHub Actions job:
 * https://github.com/jssantogit/BitChord/actions/runs/39519675488
 *
 * This neither proves that a Soulseek peer shares the exact original nor
 * authorizes arbitrary search results, download or library import. Any future
 * single licensed peer transfer must still validate final bytes against SHA.
 */
internal object A0CommonsOriginalReference {
    fun reviewedOriginal(): A0OpenAudioCandidateGate.ReviewedReference =
        A0OpenAudioCandidateGate.ReviewedReference(
            sourcePage = "https://commons.wikimedia.org/wiki/File:Ringingsound.wav",
            licenseIdentifier = "CC0-1.0",
            expectedFilename = "Ringingsound.wav",
            expectedSize = 27758L,
            independentlyKnownSha256 =
                "44254413b25cfffc3cc1e0d7c6e24aeb88cf99b867ff4e8b4088524fe0fb8325",
            rightsReviewedOutsideSoulseek = true,
        )
}
