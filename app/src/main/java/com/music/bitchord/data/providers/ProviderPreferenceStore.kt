package com.music.bitchord.data.providers

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Non-secret, versioned provider choices. This is deliberately separate from
 * SourceRegistry's encrypted connection settings and from credentials.
 *
 * Missing preferences are NOT equivalent to "all sources enabled". Runtime
 * migration must explicitly import an existing user's choices before enabling
 * this policy on any existing call path.
 */
class ProviderPreferenceStore(private val preferences: SharedPreferences) {
    sealed interface Read {
        /** New install or old installation not migrated yet. */
        data object Missing : Read

        /** Explicitly stored selections (an empty set is legal). */
        data class Loaded(
            val choices: Map<ProviderCapability, List<ProviderChoice>>,
        ) : Read

        /** Preserve original bytes; do not overwrite an unknown newer schema. */
        data object Unreadable : Read
    }

    @Serializable
    private data class StoredChoice(
        val capability: String,
        val providerId: String,
        val instanceId: String = "default",
        val enabled: Boolean,
        val priority: Int,
    )

    @Serializable
    private data class StoredDocument(
        val version: Int = VERSION,
        /** Includes explicitly empty categories, never inferred as enabled. */
        val configuredCapabilities: List<String> = emptyList(),
        val rows: List<StoredChoice> = emptyList(),
    )

    private sealed interface Decoded {
        data object Missing : Decoded
        data object Unreadable : Decoded
        data class Parsed(val document: StoredDocument) : Decoded
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): Read = when (val decoded = decode()) {
        Decoded.Missing -> Read.Missing
        Decoded.Unreadable -> Read.Unreadable
        is Decoded.Parsed -> {
            val keys = ProviderCapability.entries.associateBy { it.name }
            val recognized = decoded.document.rows.mapNotNull { row ->
                keys[row.capability]?.let { capability ->
                    capability to ProviderChoice(
                        ProviderKey(row.providerId, row.instanceId),
                        row.enabled,
                        row.priority,
                    )
                }
            }
            val byCapability = recognized.groupBy({ it.first }, { it.second })
            val configured = (decoded.document.configuredCapabilities +
                decoded.document.rows.map { it.capability }).distinct()
                .mapNotNull(keys::get)
            Read.Loaded(configured.associateWith { byCapability[it].orEmpty() })
        }
    }

    /**
     * Update exactly one capability atomically; preserve unrelated capabilities,
     * other configured server instances and future-version capability records.
     *
     * Performs durable commit on Dispatchers.IO. False means the on-disk state
     * was unreadable or commit failed, not that a source was activated.
     */
    suspend fun replaceCapability(
        capability: ProviderCapability,
        choices: List<ProviderChoice>,
    ): Boolean {
        require(choices.distinctBy { it.key }.size == choices.size) {
            "Duplicate provider instance choice for $capability"
        }
        return withContext(Dispatchers.IO) {
            synchronized(WRITE_LOCK) {
                val previous = when (val stored = decode()) {
                    Decoded.Missing -> StoredDocument()
                    Decoded.Unreadable -> return@synchronized false
                    is Decoded.Parsed -> stored.document
                }
                val next = StoredDocument(
                    configuredCapabilities = (previous.configuredCapabilities +
                        capability.name).distinct(),
                    rows = previous.rows.filterNot { it.capability == capability.name } +
                        choices.map { option ->
                            StoredChoice(
                                capability = capability.name,
                                providerId = option.key.providerId,
                                instanceId = option.key.instanceId,
                                enabled = option.enabled,
                                priority = option.order,
                            )
                        },
                )
                preferences.edit()
                    .putString(STORAGE_KEY, json.encodeToString(StoredDocument.serializer(), next))
                    .commit()
            }
        }
    }

    /**
     * One-time, atomic import of currently missing legacy categories.
     *
     * A configured empty category is a deliberate opt-out. Re-running migration
     * must NEVER enable it, or replace an existing ordering. Unknown future
     * records are retained byte-for-byte semantically, including their flags.
     * The caller supplies read-only snapshots from initialized legacy stores.
     */
    suspend fun migrateMissing(
        imported: Map<ProviderCapability, List<ProviderChoice>>,
    ): Boolean {
        imported.forEach { (capability, rows) ->
            require(rows.distinctBy { it.key }.size == rows.size) {
                "Duplicate legacy instance for $capability"
            }
        }
        return withContext(Dispatchers.IO) {
            synchronized(WRITE_LOCK) {
                val current = when (val stored = decode()) {
                    Decoded.Missing -> StoredDocument()
                    Decoded.Unreadable -> return@synchronized false
                    is Decoded.Parsed -> stored.document
                }
                val configured = (
                    current.configuredCapabilities + current.rows.map { it.capability }
                ).toSet()
                val missing = imported.filterKeys { it.name !in configured }
                if (missing.isEmpty()) return@synchronized true

                val additional = missing.flatMap { (capability, choices) ->
                    choices.map { choice ->
                        StoredChoice(
                            capability = capability.name,
                            providerId = choice.key.providerId,
                            instanceId = choice.key.instanceId,
                            enabled = choice.enabled,
                            priority = choice.order,
                        )
                    }
                }
                val next = current.copy(
                    configuredCapabilities =
                        current.configuredCapabilities + missing.keys.map { it.name },
                    rows = current.rows + additional,
                )
                if (next.rows.size > MAX_CHOICES ||
                    next.configuredCapabilities.size > MAX_CHOICES
                ) return@synchronized false

                val payload = json.encodeToString(StoredDocument.serializer(), next)
                if (payload.length > MAX_DOCUMENT_CHARS) return@synchronized false
                preferences.edit().putString(STORAGE_KEY, payload).commit()
            }
        }
    }

    private fun decode(): Decoded {
        if (!preferences.contains(STORAGE_KEY)) return Decoded.Missing
        val raw = preferences.getString(STORAGE_KEY, null) ?: return Decoded.Unreadable
        if (raw.length > MAX_DOCUMENT_CHARS) return Decoded.Unreadable
        val document = runCatching {
            json.decodeFromString(StoredDocument.serializer(), raw)
        }.getOrNull() ?: return Decoded.Unreadable
        if (document.version != VERSION || document.rows.size > MAX_CHOICES ||
            document.configuredCapabilities.size > MAX_CHOICES ||
            document.configuredCapabilities.distinct().size !=
                document.configuredCapabilities.size ||
            document.configuredCapabilities.any { it.isBlank() || it.length > MAX_NAME_CHARS }
        ) {
            return Decoded.Unreadable
        }
        val seen = HashSet<Triple<String, String, String>>()
        for (row in document.rows) {
            if (row.capability.isBlank() || row.capability.length > MAX_NAME_CHARS ||
                row.providerId.isBlank() || row.providerId.length > MAX_NAME_CHARS ||
                row.instanceId.isBlank() || row.instanceId.length > MAX_NAME_CHARS ||
                row.priority < 0 || !seen.add(Triple(row.capability, row.providerId, row.instanceId))
            ) return Decoded.Unreadable
        }
        return Decoded.Parsed(document)
    }

    companion object {
        const val STORAGE_KEY = "provider_capability_choices_v1"
        private const val VERSION = 1
        private const val MAX_CHOICES = 512
        private const val MAX_NAME_CHARS = 128
        private const val MAX_DOCUMENT_CHARS = 100_000
        private val WRITE_LOCK = Any()
    }
}
