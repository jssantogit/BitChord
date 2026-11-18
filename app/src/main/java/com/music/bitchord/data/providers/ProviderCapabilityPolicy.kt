package com.music.bitchord.data.providers

/**
 * Provider-neutral capabilities, not vendor identities or interchangeable data sources.
 *
 * Editorial products, canonical identity and edition evidence intentionally have
 * different contracts. A provider can offer several capabilities independently.
 */
enum class ProviderCapability {
    CATALOG_SEARCH,
    EDITORIAL_DISCOGRAPHY,
    IDENTITY,
    EDITION_METADATA,
    ACOUSTIC_IDENTIFICATION,
    RELEASE_ARTWORK,
    ARTIST_ARTWORK,
    /** Legacy lyric provider selection without asserting output granularity. */
    LYRICS_LOOKUP,
    LYRICS_SYNCED,
    LYRICS_PLAIN,
    CHART_MOST_PLAYED,
    CHART_SALES,
    LISTENING_STATISTICS,
    /** Account-scoped ListenBrainz suggestions, separate from listening uploads. */
    PERSONAL_RECOMMENDATIONS,
    PUBLIC_PLAYLISTS,
    PERSONAL_LIBRARY,
    AUDIO_PLAYBACK,
    /** User-configured addons/modules, not all local or remote audio. */
    EXTENSION_PLAYBACK,
    DOWNLOAD,
}

/** Provider identity plus an optional user-configured account/server instance. */
data class ProviderKey(
    val providerId: String,
    val instanceId: String = "default",
) {
    init {
        require(providerId.isNotBlank() && providerId.length <= 128)
        require(instanceId.isNotBlank() && instanceId.length <= 128)
    }
}

/**
 * Adapter implementation & static prerequisite state.
 * A registered-but-unimplemented service must NOT claim READY.
 */
enum class ProviderReadiness {
    READY,
    NEEDS_CONFIGURATION,
    NOT_IMPLEMENTED,
    BLOCKED,
}

/**
 * Dynamic runtime service health, distinct from adapter implementation and
 * user authorization. An UNAVAILABLE provider is excluded from dispatch
 * without erasing the user's saved ProviderChoice.
 */
enum class ProviderOperationalHealth {
    UNKNOWN,
    OPERATIONAL,
    DEGRADED,
    UNAVAILABLE,
}

/**
 * Explicit three-axis status for a (capability, provider instance) pair:
 * 1) whether the adapter exists in this build,
 * 2) whether static prerequisites/credentials are met,
 * 3) whether the user authorized it (and it is not quarantined) + runtime health.
 */
data class ProviderCapabilityStatus(
    val capability: ProviderCapability,
    val key: ProviderKey,
    val implemented: Boolean,
    val prerequisitesMet: Boolean,
    val userAuthorized: Boolean,
    val operationalHealth: ProviderOperationalHealth,
    val effectiveEligible: Boolean,
)

data class ProviderDescriptor(
    val key: ProviderKey,
    val capabilities: Set<ProviderCapability>,
    val readiness: ProviderReadiness = ProviderReadiness.NOT_IMPLEMENTED,
    val capabilityReadiness: Map<ProviderCapability, ProviderReadiness> = emptyMap(),
) {
    init {
        require(capabilities.isNotEmpty()) { "A provider must declare at least one capability" }
        require(capabilityReadiness.keys.all { it in capabilities }) {
            "Capability readiness must belong to this descriptor"
        }
    }

    fun readinessFor(capability: ProviderCapability): ProviderReadiness =
        if (capability in capabilities) capabilityReadiness[capability] ?: readiness
        else ProviderReadiness.NOT_IMPLEMENTED
}

/** Activation and ordering belong to a capability, not to the whole provider. */
data class ProviderChoice(
    val key: ProviderKey,
    val enabled: Boolean,
    val order: Int,
) {
    init {
        require(order >= 0)
    }
}

/**
 * Immutable, explicit policy snapshot. Nothing is implicitly enabled:
 * absent choices, disabled last choices and unavailable adapters produce an
 * empty list. A policy can also include preferences for providers not yet installed.
 *
 * Persistence, authentication and network calls belong to separate adapters.
 */
class ProviderCapabilityPolicy(
    descriptors: List<ProviderDescriptor>,
    choices: Map<ProviderCapability, List<ProviderChoice>> = emptyMap(),
    health: Map<ProviderKey, ProviderOperationalHealth> = emptyMap(),
) {
    private val byKey: Map<ProviderKey, ProviderDescriptor> = descriptors
        .also { require(it.distinctBy(ProviderDescriptor::key).size == it.size) }
        .associateBy(ProviderDescriptor::key)

    private val healthByKey: Map<ProviderKey, ProviderOperationalHealth> = health.toMap()

    private val choicesByCapability: Map<ProviderCapability, Map<ProviderKey, ProviderChoice>> =
        ProviderCapability.entries.associateWith { capability ->
            val options = choices[capability].orEmpty()
            require(options.distinctBy(ProviderChoice::key).size == options.size) {
                "A provider instance may only be selected once per capability"
            }
            options.associateBy(ProviderChoice::key)
        }

    private val eligibleByCapability: Map<ProviderCapability, List<ProviderDescriptor>> =
        ProviderCapability.entries.associateWith { capability ->
            val options = choices[capability].orEmpty()
            options.asSequence()
                .filter(ProviderChoice::enabled)
                .sortedBy(ProviderChoice::order)
                .mapNotNull { choice ->
                    byKey[choice.key]?.takeIf { descriptor ->
                        descriptor.readinessFor(capability) == ProviderReadiness.READY &&
                            capability in descriptor.capabilities &&
                            (healthByKey[descriptor.key] ?: ProviderOperationalHealth.UNKNOWN) !=
                            ProviderOperationalHealth.UNAVAILABLE
                    }
                }
                .toList()
        }

    fun eligible(capability: ProviderCapability): List<ProviderDescriptor> =
        eligibleByCapability.getValue(capability)

    fun permits(capability: ProviderCapability, key: ProviderKey): Boolean =
        eligibleByCapability.getValue(capability).any { it.key == key }

    fun status(
        capability: ProviderCapability,
        key: ProviderKey,
        quarantined: Boolean = false,
    ): ProviderCapabilityStatus {
        val descriptor = byKey[key]
        val implemented = descriptor != null &&
            descriptor.readinessFor(capability) != ProviderReadiness.NOT_IMPLEMENTED
        val prerequisitesMet = descriptor?.readinessFor(capability) == ProviderReadiness.READY
        val userAuthorized = !quarantined &&
            choicesByCapability[capability]?.get(key)?.enabled == true
        val operationalHealth = healthByKey[key] ?: ProviderOperationalHealth.UNKNOWN
        val effectiveEligible = !quarantined && permits(capability, key)
        return ProviderCapabilityStatus(
            capability = capability,
            key = key,
            implemented = implemented,
            prerequisitesMet = prerequisitesMet,
            userAuthorized = userAuthorized,
            operationalHealth = operationalHealth,
            effectiveEligible = effectiveEligible,
        )
    }
}

/**
 * A tiny admission/result guard for suspending adapters.
 *
 * Call open BEFORE dispatching network work, and accept BEFORE publishing a
 * result. Replacing the policy invalidates and notifies only the specific
 * (capability, provider instance) pairs that ceased to be permitted, leaving
 * sibling provider instances untouched.
 */
data class ProviderRequestTicket internal constructor(
    val capability: ProviderCapability,
    val key: ProviderKey,
    val revision: Long,
)

class ProviderPolicyGate(initial: ProviderCapabilityPolicy) {
    private var policy: ProviderCapabilityPolicy = initial
    private val revisions = HashMap<Pair<ProviderCapability, ProviderKey>, Long>()
    private val listeners = HashMap<Pair<ProviderCapability, ProviderKey>, MutableList<() -> Unit>>()

    @Synchronized
    fun ordered(capability: ProviderCapability): List<ProviderDescriptor> = policy.eligible(capability)

    @Synchronized
    fun open(capability: ProviderCapability, key: ProviderKey): ProviderRequestTicket? {
        if (!policy.permits(capability, key)) return null
        val slot = capability to key
        val revision = revisions.getOrPut(slot) { 1L }
        return ProviderRequestTicket(capability, key, revision)
    }

    @Synchronized
    fun accept(ticket: ProviderRequestTicket): Boolean {
        val slot = ticket.capability to ticket.key
        return revisions[slot] == ticket.revision &&
            policy.permits(ticket.capability, ticket.key)
    }

    /**
     * Registers a cancellation callback invoked immediately if [ticket] is
     * already invalid or when [replace] revokes its (capability, instance).
     */
    fun onRevoke(ticket: ProviderRequestTicket, callback: () -> Unit): () -> Unit {
        var invokeImmediately = false
        synchronized(this) {
            if (!accept(ticket)) {
                invokeImmediately = true
            } else {
                val slot = ticket.capability to ticket.key
                listeners.getOrPut(slot) { mutableListOf() }.add(callback)
            }
        }
        if (invokeImmediately) {
            callback()
            return {}
        }
        return {
            synchronized(this) {
                val slot = ticket.capability to ticket.key
                listeners[slot]?.let { list ->
                    list.remove(callback)
                    if (list.isEmpty()) listeners.remove(slot)
                }
            }
        }
    }

    fun replace(next: ProviderCapabilityPolicy) {
        val toCancel = mutableListOf<() -> Unit>()
        synchronized(this) {
            val trackedSlots = (revisions.keys + listeners.keys).toSet()
            for (slot in trackedSlots) {
                val (capability, key) = slot
                if (!next.permits(capability, key)) {
                    revisions[slot] = (revisions[slot] ?: 0L) + 1L
                    listeners.remove(slot)?.let { toCancel.addAll(it) }
                }
            }
            policy = next
        }
        toCancel.forEach { callback -> runCatching { callback() } }
    }
}
