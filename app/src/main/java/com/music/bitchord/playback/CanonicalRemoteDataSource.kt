package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routes credential-free canonical URIs before Playback's generic stream stack.
 *
 * Provider factories receive only canonical identity. Any authenticated request
 * stays inside the provider-owned DataSource returned by that factory.
 */
internal class CanonicalRemoteDataSource(
    private val fallbackFactory: DataSource.Factory,
    private val openSubsonicFactory: (
        recordingId: String,
        mediaAssetId: String,
        providerKey: String,
    ) -> DataSource,
) : DataSource {
    private val transferListeners = CopyOnWriteArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        if (active != null) throw IOException("Canonical remote data source already open")

        val logicalValue = dataSpec.uri.toString()
        val request = CanonicalRemotePlaybackUri.decode(logicalValue)
        val source = when {
            request != null && request.providerKey.startsWith(OPENSUBSONIC_PROVIDER_PREFIX) ->
                openSubsonicFactory(
                    request.recordingId,
                    request.mediaAssetId,
                    request.providerKey,
                )

            request != null ->
                throw IOException("Canonical remote playback provider unsupported")

            isCanonicalAuthority(dataSpec.uri) ->
                throw IOException("Invalid canonical remote playback request")

            else -> fallbackFactory.createDataSource()
        }

        transferListeners.forEach(source::addTransferListener)
        active = source
        return try {
            source.open(dataSpec)
        } catch (error: Exception) {
            active = null
            runCatching { source.close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length)
            ?: throw IOException("Canonical remote data source is not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun close() {
        val current = active
        active = null
        current?.close()
    }

    private fun isCanonicalAuthority(uri: Uri): Boolean =
        uri.scheme.equals("bitchord", ignoreCase = true) && uri.authority == "canonical"

    internal class Factory(
        private val fallbackFactory: DataSource.Factory,
        private val openSubsonicFactory: (
            recordingId: String,
            mediaAssetId: String,
            providerKey: String,
        ) -> DataSource,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = CanonicalRemoteDataSource(
            fallbackFactory = fallbackFactory,
            openSubsonicFactory = openSubsonicFactory,
        )
    }

    private companion object {
        const val OPENSUBSONIC_PROVIDER_PREFIX = "opensubsonic:"
    }
}
