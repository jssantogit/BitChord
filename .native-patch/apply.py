from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one bounded match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


SERVICE = "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
PLAYER = "app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt"
CACHE = "app/src/main/java/com/music/bitchord/playback/AudioCache.kt"
UI = "app/src/main/java/com/music/bitchord/ui/screens/CanonicalRemoteLibraryScreen.kt"

replace_once(
    SERVICE,
    "package com.music.bitchord.playback\n",
    "package com.music.bitchord.playback\n\n"
    "import com.music.bitchord.data.catalog.provider.opensubsonic.androidOpenSubsonicMediaAccess\n"
    "import com.music.bitchord.data.catalog.provider.opensubsonic.materializeDataSpec\n",
)

replace_once(
    SERVICE,
    "    private val boundedContextCommand = SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)\n"
    "    private val clearBoundedContextCommand = SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY)\n",
    "    private val boundedContextCommand = SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)\n"
    "    private val clearBoundedContextCommand = SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY)\n"
    "    private val canonicalRemotePlaybackCommand = SessionCommand(ACTION_PLAY_CANONICAL_REMOTE, Bundle.EMPTY)\n",
)

replace_once(
    SERVICE,
    "        val streamResolver = ResolvingDataSource.Resolver { dataSpec ->\n",
    "        val canonicalRemoteResolver = CanonicalRemotePlaybackResolver(\n"
    "            openSubsonicResolve = androidOpenSubsonicMediaAccess(this)::resolve,\n"
    "        )\n\n"
    "        val streamResolver = ResolvingDataSource.Resolver { dataSpec ->\n"
    "            if (CanonicalRemotePlaybackUri.isCanonical(dataSpec.uri.toString())) {\n"
    "                val runtimeRequest = try {\n"
    "                    runBlocking {\n"
    "                        withTimeout(RESOLVE_TIMEOUT_MS) {\n"
    "                            canonicalRemoteResolver.resolve(dataSpec.uri.toString())\n"
    "                        }\n"
    "                    }\n"
    "                } catch (e: TimeoutCancellationException) {\n"
    "                    throw java.io.IOException(\"Canonical remote playback resolution timed out\", e)\n"
    "                }\n"
    "                return@Resolver runtimeRequest.materializeDataSpec(dataSpec)\n"
    "            }\n",
)

replace_once(
    SERVICE,
    "            streamResolver.resolveDataSpec(dataSpec).also { resolved ->\n"
    "                mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }\n"
    "                // The same seam tells the cache whose file it is writing, which\n"
    "                // decides whether the entry outlives the track — see\n"
    "                // [AudioCache.Origin].\n"
    "                AudioCache.recordServed(dataSpec, resolved.uri.toString())\n"
    "            }\n",
    "            if (CanonicalRemotePlaybackUri.isCanonical(dataSpec.uri.toString())) {\n"
    "                // The resolved URI contains ephemeral provider auth. Never hand it\n"
    "                // to legacy stream telemetry/cache bookkeeping.\n"
    "                streamResolver.resolveDataSpec(dataSpec)\n"
    "            } else {\n"
    "                streamResolver.resolveDataSpec(dataSpec).also { resolved ->\n"
    "                    mediaIdIn(dataSpec.uri)?.let { StreamContainer.served(it, resolved.uri.toString()) }\n"
    "                    // The same seam tells the cache whose file it is writing, which\n"
    "                    // decides whether the entry outlives the track — see\n"
    "                    // [AudioCache.Origin].\n"
    "                    AudioCache.recordServed(dataSpec, resolved.uri.toString())\n"
    "                }\n"
    "            }\n",
)

replace_once(
    SERVICE,
    "    private fun canLike(item: MediaItem?): Boolean =\n"
    "        item != null && SignedIn.state(this).value && item.toSong().localUri == null\n",
    "    private fun canLike(item: MediaItem?): Boolean =\n"
    "        item != null &&\n"
    "            !item.isCanonicalRemotePlayback() &&\n"
    "            SignedIn.state(this).value &&\n"
    "            item.toSong().localUri == null\n",
)

replace_once(
    SERVICE,
    "    private fun carButtons(): List<CommandButton> {\n"
    "        val current = player?.currentMediaItem?.toSong()\n",
    "    private fun carButtons(): List<CommandButton> {\n"
    "        val current = player?.currentMediaItem\n"
    "            ?.takeUnless { it.isCanonicalRemotePlayback() }\n"
    "            ?.toSong()\n",
)

replace_once(
    SERVICE,
    "    private fun startStationFromSession() {\n"
    "        val originalPlayer = player ?: return\n"
    "        val currentItem = originalPlayer.currentMediaItem ?: return\n"
    "        val seed = currentItem.toSong().takeIf { it.canStartStation() } ?: return\n",
    "    private fun startStationFromSession() {\n"
    "        val originalPlayer = player ?: return\n"
    "        val currentItem = originalPlayer.currentMediaItem ?: return\n"
    "        if (currentItem.isCanonicalRemotePlayback()) return\n"
    "        val seed = currentItem.toSong().takeIf { it.canStartStation() } ?: return\n",
)

replace_once(
    SERVICE,
    "    private fun loadAutoplayForCurrentTrack() {\n"
    "        val exoPlayer = player ?: return\n"
    "        val party = ListenTogether.state.value\n",
    "    private fun loadAutoplayForCurrentTrack() {\n"
    "        val exoPlayer = player ?: return\n"
    "        if (exoPlayer.currentMediaItem?.isCanonicalRemotePlayback() == true) return\n"
    "        val party = ListenTogether.state.value\n",
)

replace_once(
    SERVICE,
    "    private fun lookForBetterCopy(player: ExoPlayer) {\n"
    "        val item = player.currentMediaItem ?: return\n"
    "        val mediaId = item.mediaId\n",
    "    private fun lookForBetterCopy(player: ExoPlayer) {\n"
    "        val item = player.currentMediaItem ?: return\n"
    "        if (item.isCanonicalRemotePlayback()) return\n"
    "        val mediaId = item.mediaId\n",
)

replace_once(
    SERVICE,
    "    private fun upgradeQualityNow() {\n"
    "        val player = player ?: return\n"
    "        val mediaId = player.currentMediaItem?.mediaId ?: return\n",
    "    private fun upgradeQualityNow() {\n"
    "        val player = player ?: return\n"
    "        if (player.currentMediaItem?.isCanonicalRemotePlayback() == true) return\n"
    "        val mediaId = player.currentMediaItem?.mediaId ?: return\n",
)

replace_once(
    SERVICE,
    "    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {\n"
    "        val item = player.currentMediaItem ?: return\n"
    "        val mediaId = item.mediaId\n",
    "    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {\n"
    "        val item = player.currentMediaItem ?: return\n"
    "        // Provider-specific failures are terminal for this direct-play vertical;\n"
    "        // do not reinterpret the canonical id as a YouTube/source fallback key.\n"
    "        if (item.isCanonicalRemotePlayback()) return\n"
    "        val mediaId = item.mediaId\n",
)

replace_once(
    SERVICE,
    "        val uri = item.localConfiguration?.uri ?: return@withContext null\n"
    "        val resolver = streamResolver ?: return@withContext null\n",
    "        if (item.isCanonicalRemotePlayback()) return@withContext null\n"
    "        val uri = item.localConfiguration?.uri ?: return@withContext null\n"
    "        val resolver = streamResolver ?: return@withContext null\n",
)

replace_once(
    SERVICE,
    "            (nextIndex..end).map { index -> player.getMediaItemAt(index).toSong() }\n",
    "            (nextIndex..end).mapNotNull { index ->\n"
    "                val item = player.getMediaItemAt(index)\n"
    "                item.takeUnless { it.isCanonicalRemotePlayback() }?.toSong()\n"
    "            }\n",
)

replace_once(
    SERVICE,
    "        LastPlayed.clearImmediately()\n"
    "    }\n\n"
    "    /** Persist index and position without touching or serializing queue contents. */\n",
    "        LastPlayed.clearImmediately()\n"
    "    }\n\n"
    "    /** Start one canonical remote item without exposing provider-native identity. */\n"
    "    private fun startCanonicalRemotePlayback(args: Bundle) {\n"
    "        val exoPlayer = player ?: return\n"
    "        val (request, metadata) = canonicalRemotePlaybackFromBundle(args) ?: return\n"
    "        beginRadioQueue()\n"
    "        exoPlayer.setMediaItem(canonicalRemoteMediaItem(request, metadata))\n"
    "        exoPlayer.prepare()\n"
    "        exoPlayer.play()\n"
    "        saveQueueSnapshotImmediately(exoPlayer)\n"
    "    }\n\n"
    "    /** Persist index and position without touching or serializing queue contents. */\n",
)

replace_once(
    SERVICE,
    "                .add(clearBoundedContextCommand)\n"
    "                .add(autoplayCommand)\n",
    "                .add(clearBoundedContextCommand)\n"
    "                .add(canonicalRemotePlaybackCommand)\n"
    "                .add(autoplayCommand)\n",
)

replace_once(
    SERVICE,
    "                ACTION_START_BOUNDED_CONTEXT -> startBoundedContext(args)\n"
    "                ACTION_CLEAR_BOUNDED_CONTEXT -> clearBoundedContext()\n"
    "                ACTION_TOGGLE_AUTOPLAY -> toggleAutoplayFromNotification()\n",
    "                ACTION_START_BOUNDED_CONTEXT -> startBoundedContext(args)\n"
    "                ACTION_CLEAR_BOUNDED_CONTEXT -> clearBoundedContext()\n"
    "                ACTION_PLAY_CANONICAL_REMOTE -> startCanonicalRemotePlayback(args)\n"
    "                ACTION_TOGGLE_AUTOPLAY -> toggleAutoplayFromNotification()\n",
)

replace_once(
    PLAYER,
    "/** Routes the player-screen AutoPlay button through the playback service. */\n",
    "/** Starts provider-backed canonical playback using only safe session-boundary data. */\n"
    "fun MediaController.playCanonicalRemote(\n"
    "    request: CanonicalRemotePlaybackRequest,\n"
    "    metadata: CanonicalRemotePlaybackMetadata,\n"
    ") {\n"
    "    sendCustomCommand(\n"
    "        SessionCommand(ACTION_PLAY_CANONICAL_REMOTE, Bundle.EMPTY),\n"
    "        canonicalRemotePlaybackBundle(request, metadata),\n"
    "    )\n"
    "}\n\n"
    "/** Routes the player-screen AutoPlay button through the playback service. */\n",
)

replace_once(
    CACHE,
    "                activeDs = if (scheme == \"file\" || scheme == \"content\") {\n"
    "                    upstreamDs\n"
    "                } else {\n"
    "                    cacheDs\n"
    "                }\n",
    "                activeDs = if (\n"
    "                    scheme == \"file\" ||\n"
    "                    scheme == \"content\" ||\n"
    "                    CanonicalRemotePlaybackUri.isCanonical(dataSpec.uri.toString())\n"
    "                ) {\n"
    "                    // Canonical remote direct play deliberately has no offline/cache\n"
    "                    // semantics in this vertical; auth is materialized at each open.\n"
    "                    upstreamDs\n"
    "                } else {\n"
    "                    cacheDs\n"
    "                }\n",
)

replace_once(
    UI,
    "import androidx.activity.compose.BackHandler\n",
    "import androidx.activity.compose.BackHandler\nimport androidx.compose.foundation.clickable\n",
)

replace_once(
    UI,
    "import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore\n",
    "import com.music.bitchord.data.catalog.RoomCanonicalLibraryBrowseStore\n"
    "import com.music.bitchord.playback.CanonicalRemotePlaybackMetadata\n"
    "import com.music.bitchord.playback.CanonicalRemotePlaybackRequest\n"
    "import com.music.bitchord.playback.playCanonicalRemote\n"
    "import com.music.bitchord.playback.rememberMediaController\n",
)

replace_once(
    UI,
    "    val context = LocalContext.current\n"
    "    val repository = remember(context.applicationContext) {\n",
    "    val context = LocalContext.current\n"
    "    val controller = rememberMediaController()\n"
    "    val repository = remember(context.applicationContext) {\n",
)

replace_once(
    UI,
    "            TrackList(\n"
    "                tracks = library.tracks,\n"
    "                modifier = Modifier.fillMaxSize(),\n"
    "            )\n",
    "            TrackList(\n"
    "                tracks = library.tracks,\n"
    "                onTrackPlay = { track ->\n"
    "                    controller?.playCanonicalRemote(\n"
    "                        request = CanonicalRemotePlaybackRequest(\n"
    "                            recordingId = track.recordingId,\n"
    "                            mediaAssetId = track.mediaAssetId,\n"
    "                            providerKey = track.providerKey,\n"
    "                        ),\n"
    "                        metadata = CanonicalRemotePlaybackMetadata(\n"
    "                            title = track.title,\n"
    "                            artistDisplay = track.artistDisplay,\n"
    "                        ),\n"
    "                    )\n"
    "                },\n"
    "                modifier = Modifier.fillMaxSize(),\n"
    "            )\n",
)

replace_once(
    UI,
    "            Text(\n"
    "                text = stringResource(R.string.remote_library_playback_deferred),\n"
    "                style = MaterialTheme.typography.bodySmall,\n"
    "                color = MaterialTheme.colorScheme.onSurfaceVariant,\n"
    "                modifier = Modifier.padding(top = 2.dp),\n"
    "            )\n",
    "",
)

replace_once(
    UI,
    "private fun TrackList(\n"
    "    tracks: List<CanonicalBrowseTrack>,\n"
    "    modifier: Modifier = Modifier,\n"
    ") {\n",
    "private fun TrackList(\n"
    "    tracks: List<CanonicalBrowseTrack>,\n"
    "    onTrackPlay: (CanonicalBrowseTrack) -> Unit,\n"
    "    modifier: Modifier = Modifier,\n"
    ") {\n",
)

replace_once(
    UI,
    "        ) { track ->\n"
    "            CanonicalTrackRow(track = track)\n",
    "        ) { track ->\n"
    "            CanonicalTrackRow(track = track, onClick = { onTrackPlay(track) })\n",
)

replace_once(
    UI,
    "@Composable\n"
    "private fun CanonicalTrackRow(track: CanonicalBrowseTrack) {\n"
    "    // Deliberately not clickable: remote playback belongs to PlaybackResolver.\n"
    "    // Making this row playable now would require fabricating a legacy Song id.\n"
    "    Row(\n",
    "@Composable\n"
    "private fun CanonicalTrackRow(\n"
    "    track: CanonicalBrowseTrack,\n"
    "    onClick: () -> Unit,\n"
    ") {\n"
    "    Row(\n",
)

replace_once(
    UI,
    "        modifier = Modifier\n"
    "            .fillMaxWidth()\n"
    "            .heightIn(min = 68.dp)\n"
    "            .padding(horizontal = 20.dp, vertical = 8.dp),\n",
    "        modifier = Modifier\n"
    "            .fillMaxWidth()\n"
    "            .clickable(onClick = onClick)\n"
    "            .heightIn(min = 68.dp)\n"
    "            .padding(horizontal = 20.dp, vertical = 8.dp),\n",
)

print("bounded canonical remote integration patch applied")
