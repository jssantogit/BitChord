package com.music.bitchord.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes playback facts before they touch Listening & Intelligence state.
 *
 * Media3 can deliver related callbacks back-to-back while identity resolution
 * and Room writes suspend. A plain `scope.launch` per callback allows a later
 * transition to overtake the end fact that belongs to the previous track. This
 * queue preserves submission order across those suspensions without moving
 * session policy into Playback.
 */
internal class PlaybackListeningCommandQueue(
    scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
) {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onFailure(error)
                }
            }
        }
    }

    fun submit(command: suspend () -> Unit) {
        check(commands.trySend(command).isSuccess) {
            "Playback listening command queue is closed"
        }
    }
}
