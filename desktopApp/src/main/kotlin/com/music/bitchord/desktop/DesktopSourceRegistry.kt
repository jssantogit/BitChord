package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** What playback got to start with, and what is still being looked for. */
internal class DesktopLiveResolution(
    val stream: DesktopStream,
    /**
     * The substitution search that had not finished when sound started, or null when nothing is
     * outstanding.
     */
    val pendingSubstitute: Deferred<DesktopStream?>? = null,
)

internal data class DesktopSourceDescriptor(
    val id: String,
    val name: String,
    val detail: String,
    val kind: DesktopSourceKind,
    val configured: Boolean,
    val enabled: Boolean,
    val supportsLossless: Boolean,
    /** Enabled, but the ceiling in force will not let it answer a stream. */
    val skippedByQuality: Boolean = false,
)

/** Desktop counterpart of Android's SourceRegistry. */
internal object DesktopSourceRegistry {
    private interface SourceAdapter {
        val config: DesktopSourceConfig
        val descriptor: DesktopSourceDescriptor
        suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?>
        /** [resolve] for a row this source owns, played as itself rather than as a match. */
        suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> = resolve(song, quality)
        /** Every copy this source holds of [song], most confident first. */
        suspend fun matches(song: Song): List<Song> = emptyList()

        suspend fun match(song: Song): Song? = matches(song).firstOrNull()
        fun owns(song: Song): Boolean = false
    }

    private class AddonAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopAddonSource.parseTrack(song.videoId)?.sourceId == config.id

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality)

        override suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality, picked = true)

        override suspend fun matches(song: Song): List<Song> = DesktopAddonSource.matches(config, song)
    }

    private class ModuleAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopModuleSource.parseTrack(song.videoId)?.let { ref ->
                ref.sourceId == null || ref.sourceId == config.id
            } == true

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopModuleSource.stream(config, song, quality)

        override suspend fun matches(song: Song): List<Song> = DesktopModuleSource.matches(config, song)
    }

    private class JioAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean = song.videoId.startsWith("jiosaavn:")

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopJioSaavn.stream(song.videoId)

        override suspend fun matches(song: Song): List<Song> = DesktopJioSaavn.matches(song)
    }

    /** Historical video IDs are metadata, never substitutes for playable assets. */
    suspend fun youTubeIdentity(@Suppress("UNUSED_PARAMETER") song: Song): Song? = null

    /**
     * Whether resolving [song] could ask an addon for it — an addon's own row, or a YouTube row
     * with an addon ranked above YouTube to substitute it.
     *
     * Such a track is not read ahead: an addon is only asked for a stream once the track is about
     * to play, as on Android, whose read-ahead only warms sources that are
     * `worthPrefetching`.
     */
    fun mayServeFromAddon(song: Song): Boolean {
        val addons = playbackAdapters(null).filterIsInstance<AddonAdapter>()
        if (addons.isEmpty()) return false
        if (DesktopAddonSource.parseTrack(song.videoId) != null) return true
        return false
    }

    private fun DesktopSourceConfig.descriptor() = DesktopSourceDescriptor(
        id = id,
        name = displayName,
        detail = if (kind.needsServer && baseUrl.isBlank()) "Setup required" else kind.detail,
        kind = kind,
        configured = isComplete,
        enabled = enabled,
        supportsLossless = kind.supportsLossless,
        skippedByQuality = enabled && !DesktopPersistence().audioQuality().permits(kind),
    )

    private fun configs(): List<DesktopSourceConfig> = DesktopPersistence().sourceConfigs()

    /**
     * The sources a *stream* may come from: enabled, complete, and permitted by the ceiling in
     * force.
     */
    private fun playbackAdapters(quality: String?, forDownload: Boolean = false): List<SourceAdapter> {
        val ceiling = ceiling(quality)
        return adapters(forDownload).filter { ceiling.permits(it.config.kind) }
    }

    /**
     * For a download, minus any addon whose manifest says `allowDownloads: 0`, so the next source
     * in line serves the file.
     */
    private fun adapters(forDownload: Boolean = false): List<SourceAdapter> =
        configs()
            .inSourceOrder()
            .filter { it.enabled && it.isComplete && it.kind != DesktopSourceKind.YOUTUBE }
            .filterNot { forDownload && it.kind == DesktopSourceKind.ADDON && !DesktopAddonSource.allowsDownloads(it) }
            .mapNotNull { config ->
                when (config.kind) {
                    DesktopSourceKind.ADDON -> AddonAdapter(config)
                    DesktopSourceKind.CUSTOM_MODULE,
                    DesktopSourceKind.MODULE,
                    -> ModuleAdapter(config)
                    DesktopSourceKind.JIOSAAVN -> JioAdapter(config)
                    DesktopSourceKind.YOUTUBE -> null // deserialization only
                }
            }

    fun descriptors(): List<DesktopSourceDescriptor> = configs()
        .filterNot { it.kind == DesktopSourceKind.YOUTUBE }
        .inSourceOrder()
        .map { it.descriptor() }

    fun isEnabled(sourceId: String): Boolean = configs()
        .firstOrNull { it.id == sourceId && it.kind != DesktopSourceKind.YOUTUBE }
        ?.enabled == true

    /** Searching is YouTube Music's job, and only YouTube Music's. */
    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        DesktopSearchClient.search(query, filter)

    /**
     * Resolve only a source-owned track. Opaque video IDs and historical
     * metadata cannot become a MediaAsset by matching a title to another file.
     * Explicit source identities may still fall through to a verified match
     * when their own server is down or forbids downloads.
     */
    suspend fun resolve(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
        forDownload: Boolean = false,
    ): Result<DesktopStream> = runCatching {
        val available = playbackAdapters(quality, forDownload)
            .filterNot { it.config.id == excludedSourceId }
        val moduleReference = DesktopModuleSource.parseTrack(song.videoId)
        val addonReference = DesktopAddonSource.parseTrack(song.videoId)
        val isJio = song.videoId.startsWith("jiosaavn:")
        if (addonReference == null && moduleReference == null && !isJio) {
            error("This historical track has no verified audio source")
        }
        val owned = when {
            addonReference != null -> available.firstOrNull { it.config.id == addonReference.sourceId }
            moduleReference?.sourceId != null -> available.firstOrNull { it.config.id == moduleReference.sourceId }
            moduleReference != null -> available.firstOrNull {
                it.config.kind in setOf(DesktopSourceKind.CUSTOM_MODULE, DesktopSourceKind.MODULE)
            }
            isJio -> available.firstOrNull { it.config.kind == DesktopSourceKind.JIOSAAVN }
            else -> null
        }
        val canFallback = addonReference != null && forDownload &&
            configs().any {
                it.id == addonReference.sourceId && it.enabled && it.isComplete &&
                    !DesktopAddonSource.allowsDownloads(it)
            }
        if (owned == null && !canFallback) {
            error("The original source is unavailable or no longer configured")
        }
        if (owned != null) {
            owned.resolveOwned(song, quality).getOrNull()
                ?.takeIf { playableHere(owned, it) }
                ?.let { return@runCatching it }
        }
        for (fallback in available.filterNot { it === owned }) {
            openBest(fallback, song, quality)?.let { return@runCatching it }
        }
        error("No configured provider can serve this recording")
    }

    /** Play a provider-verified stream; never race an archival YouTube URL. */
    suspend fun resolveLive(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
    ): Result<DesktopLiveResolution> =
        resolve(song, quality, excludedSourceId).map(::DesktopLiveResolution)

    /** The first usable stream from any of [sources], each asked in parallel. */
    private suspend fun firstSubstitute(
        sources: List<SourceAdapter>,
        song: Song,
        quality: String?,
    ): DesktopStream? = coroutineScope {
        val running = sources
            .map { source ->
                async { openBest(source, song, quality) }
            }
            .toMutableList()
        try {
            while (running.isNotEmpty()) {
                val done = select<Deferred<DesktopStream?>> {
                    running.forEach { candidate -> candidate.onAwait { candidate } }
                }
                running -= done
                done.await()?.let { return@coroutineScope it }
            }
            null
        } finally {
            running.forEach { it.cancel() }
        }
    }

    /** Whether a copy found after the fact is worth interrupting playback for. */
    internal fun worthSwapping(candidate: DesktopStreamFormat, playing: DesktopStreamFormat?): Boolean {
        if (candidate.isLossless) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * A second look, asked from scratch once the first one has settled for nothing better.
     *
     * The live race deliberately takes the first copy that beats YouTube, so a slower lossless
     * source can still be searching when a lossy one wins — and a source that answered HTTP 502
     * that minute may well answer properly now. Mirrors Android's `SourceResolver.upgradeFor`.
     */
    suspend fun upgradeFor(
        song: Song,
        playing: DesktopStream?,
        quality: String? = null,
    ): DesktopStream? {
        // This is an explicit second look. Empty catalogue answers and failed/expired stream URLs
        // from the first pass must not make the retry a cache hit.
        DesktopAddonSource.clearCompletedTrackCalls()
        val sourceId = playing?.sourceId ?: return null
        val playingRank = configs()
            .firstOrNull { it.id == sourceId && it.kind != DesktopSourceKind.YOUTUBE }
            ?.kind?.rank ?: return null
        val better = playbackAdapters(quality).filter {
            it.config.id != sourceId && it.config.kind.rank < playingRank
        }
        if (better.isEmpty()) return null
        DesktopTrackLog.log(
            "asking ${better.joinToString { it.descriptor.name }} again for a better copy of " +
                "'${song.title}'",
        )
        return withTimeoutOrNull(UPGRADE_TIMEOUT_MS) { firstSubstitute(better, song, quality) }
    }

    /** Whether a stream a source handed back can actually be played here. */
    /**
     * The best copy [source] can actually serve for [song], or null when it has none.
     *
     * More than one row is opened, because a catalogue that holds a track can still fail on the
     * particular row matched first — and rows advertising the tier being asked for go first.
     */
    private suspend fun openBest(
        source: SourceAdapter,
        song: Song,
        quality: String?,
    ): DesktopStream? {
        val matches = source.matches(song)
        if (matches.isEmpty()) {
            // A leg cancelled by a sibling winning the race has not missed anything.
            if (currentCoroutineContext().isActive) {
                DesktopTrackLog.log("${source.descriptor.name} has no copy of '${song.title}'")
            }
            return null
        }
        val wantsLossless = ceiling(quality) == DesktopAudioQuality.LOSSLESS
        var settleFor: DesktopStream? = null
        for (match in preferred(matches, song, wantsLossless).take(STREAM_ATTEMPTS)) {
            val stream = source.resolve(match, quality).getOrNull() ?: continue
            if (!playableHere(source, stream)) continue
            val served = stream.format
            if (!wantsLossless || served.isLossless || served.isDolbyAtmos) {
                DesktopTrackLog.log(
                    "${source.descriptor.name} matched '${match.title}' by '${match.artist}'" +
                        " → ${served.summary.ifBlank { "an unstated format" }}",
                )
                return stream
            }
            DesktopTrackLog.log(
                "${source.descriptor.name} offered ${served.summary.ifBlank { "an unstated format" }}" +
                    " for '${match.title}'; looking further",
            )
            // The floor is the best of what was refused, not the first of it.
            settleFor = betterOf(settleFor, stream)
        }
        if (settleFor == null && currentCoroutineContext().isActive) {
            DesktopTrackLog.log("${source.descriptor.name} matched '${song.title}' but returned no stream")
        }
        return settleFor
    }

    /** The matching rows in the order they are worth opening. */
    internal fun preferred(matches: List<Song>, target: Song, wantsLossless: Boolean): List<Song> {
        val sameLength = matches.filter { DesktopTrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        if (!wantsLossless) return eligible
        // Stable, so the confidence order the matcher produced survives inside each tier.
        return eligible.sortedByDescending { it.sourceQuality == DesktopModuleSource.LOSSLESS }
    }

    /** Which of two renditions of the same recording is the better one. */
    private fun betterOf(current: DesktopStream?, candidate: DesktopStream): DesktopStream {
        val held = current ?: return candidate
        val a = candidate.format
        val b = held.format
        if (a.isLossless != b.isLossless) return if (a.isLossless) candidate else held
        if (a.isDolbyAtmos != b.isDolbyAtmos) return if (a.isDolbyAtmos) candidate else held
        return if ((a.kbps ?: 0) > (b.kbps ?: 0)) candidate else held
    }

    private fun playableHere(source: SourceAdapter, stream: DesktopStream): Boolean {
        if (stream.isDolbyAtmos && !DesktopCodecs.supportsDolbyAtmos) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, " +
                    "which this build has no E-AC-3 decoder for",
            )
            return false
        }
        if (stream.isDolbyAtmos && !DesktopAddonSettings.dolbyAtmosEnabled) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, which is switched off",
            )
            return false
        }
        return true
    }

    /** Whether this row has a YouTube upload behind it to go back to. */
    fun hasYouTubeOriginal(@Suppress("UNUSED_PARAMETER") song: Song): Boolean = false

    /** The configured source a stream came from, for a log line. */
    internal fun sourceNameFor(stream: DesktopStream): String =
        configs().firstOrNull { it.id == stream.sourceId }?.displayName ?: "a configured source"

    /**
     * The patient second look's budget. Longer than the race's, because nobody is waiting on the
     * first note by then and a cold addon routinely needs more than one call to settle.
     */
    private const val UPGRADE_TIMEOUT_MS = 90_000L

    /** How many matched rows are opened before a source is written off. */
    private const val STREAM_ATTEMPTS = 3

    /** The runtime agreement that says two rows are the same recording. */
    private const val SAME_RECORDING_SEC = 2

    /** The smallest bitrate gain worth a break in the audio for. */
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    /** The ceiling a call is made under: an explicit override, else the stored rung. */
    internal fun ceiling(qualityOverride: String?): DesktopAudioQuality =
        qualityOverride?.let { override ->
            DesktopAudioQuality.entries.firstOrNull { it.name.equals(override, ignoreCase = true) }
        } ?: DesktopPersistence().audioQuality()

}
