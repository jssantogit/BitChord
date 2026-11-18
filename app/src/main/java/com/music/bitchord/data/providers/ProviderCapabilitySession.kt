package com.music.bitchord.data.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One source of truth for admission decisions within the new provider engine.
 *
 * The instance starts closed. It only publishes READY descriptors that are
 * also explicitly enabled in a valid persisted/migrated policy. Existing
 * search/playback paths do not use the session until their cutover is complete.
 */
class ProviderCapabilitySession(
    private val store: ProviderPreferenceStore,
    initialDescriptors: List<ProviderDescriptor>,
) {
    private val lock = Mutex()
    private var descriptors: List<ProviderDescriptor> = initialDescriptors.toList()
    private var choices: Map<ProviderCapability, List<ProviderChoice>> = emptyMap()
    // Granular per-instance quarantine: (capability, provider key)
    private val quarantinedSlots = mutableSetOf<Pair<ProviderCapability, ProviderKey>>()
    private val health = mutableMapOf<ProviderKey, ProviderOperationalHealth>()
    private var initialized: Boolean = false
    private var storageFailure: Boolean = false

    /** Admission gate shared by adapters; closed until initialization. */
    val gate = ProviderPolicyGate(ProviderCapabilityPolicy(initialDescriptors))
    private val _state = MutableStateFlow(ProviderSessionSnapshot())
    /** Immutable, non-secret status for future presentation or diagnostics. */
    val state: StateFlow<ProviderSessionSnapshot> = _state.asStateFlow()

    private fun publishSnapshot() {
        val status = when {
            storageFailure -> ProviderSessionStatus.STORAGE_ERROR
            !initialized -> ProviderSessionStatus.BOOTING
            quarantinedSlots.isNotEmpty() -> ProviderSessionStatus.PERSISTING
            else -> ProviderSessionStatus.READY
        }
        val eligible = ProviderCapability.entries.mapNotNull { capability ->
            gate.ordered(capability).map { it.key }
                .takeIf { it.isNotEmpty() }
                ?.let { capability to it }
        }.toMap()
        _state.value = ProviderSessionSnapshot(
            status = status,
            configured = choices.mapValues { (_, selections) -> selections.toList() },
            eligible = eligible,
            quarantined = quarantinedSlots.map { it.first }.toSet(),
            quarantinedProviders = quarantinedSlots.toSet(),
            health = health.toMap(),
        )
    }

    private fun effectivePolicy(): ProviderCapabilityPolicy {
        // Construct choices omitting quarantined instances
        val adjustedChoices = choices.map { (capability, options) ->
            capability to options.map { choice ->
                if ((capability to choice.key) in quarantinedSlots) {
                    choice.copy(enabled = false)
                } else {
                    choice
                }
            }
        }.toMap()
        return ProviderCapabilityPolicy(
            descriptors = descriptors,
            choices = adjustedChoices,
            health = health,
        )
    }

    /**
     * Import only missing legacy categories. An existing explicit opt-out
     * always wins. Corrupt/newer documents must NEVER acquire default choices.
     */
    suspend fun initialize(
        legacyChoices: Map<ProviderCapability, List<ProviderChoice>>,
    ): Boolean = lock.withLock {
        if (!store.migrateMissing(legacyChoices)) {
            failClosed(storageError = true)
            return@withLock false
        }
        val loaded = when (val snapshot = store.read()) {
            is ProviderPreferenceStore.Read.Loaded -> snapshot.choices
            // A genuinely fresh installation has no provider preferences.
            // Empty is valid: the user must still be able to opt into a
            // first source without inheriting a fake mandatory fallback.
            ProviderPreferenceStore.Read.Missing ->
                if (legacyChoices.isEmpty()) emptyMap() else null
            ProviderPreferenceStore.Read.Unreadable -> null
        }
        if (loaded == null) {
            failClosed(storageError = true)
            return@withLock false
        }
        choices = loaded
        quarantinedSlots.clear()
        initialized = true
        storageFailure = false
        gate.replace(effectivePolicy())
        publishSnapshot()
        true
    }

    /**
     * Immediate instance-local revocation before durable I/O.
     *
     * A failed commit keeps the affected provider instances quarantined even when
     * another instance is edited later. A subsequent successful commit for
     * that capability recovers it cleanly without re-enabling opt-outs.
     */
    suspend fun update(
        capability: ProviderCapability,
        next: List<ProviderChoice>,
    ): Boolean = lock.withLock {
        if (!initialized) return@withLock false
        require(next.distinctBy { it.key }.size == next.size) {
            "Duplicate provider instance for $capability"
        }
        if (!storageFailure &&
            choices[capability] == next &&
            quarantinedSlots.none { it.first == capability }
        ) {
            return@withLock true // same durable preference, no revocation or I/O
        }

        // Revoke ONLY currently admitted instances that the next selection
        // disables or removes. This includes next == emptyList(): we MUST
        // close the last source BEFORE a potentially failing disk commit.
        // Never quarantine siblings that remain eligible, or newly enabled
        // sources that are still waiting for durable authorization.
        val nextEnabled = next.asSequence()
            .filter(ProviderChoice::enabled)
            .map(ProviderChoice::key)
            .toSet()
        val newlyRevoked = gate.ordered(capability)
            .map { capability to it.key }
            .filter { (_, key) -> key !in nextEnabled }
        quarantinedSlots.addAll(newlyRevoked)
        gate.replace(effectivePolicy())
        publishSnapshot()

        if (!store.replaceCapability(capability, next)) {
            // Keep revoked slots closed, but do not disturb unrelated siblings.
            storageFailure = true
            publishSnapshot()
            return@withLock false
        }

        choices = choices + (capability to next.toList())
        // All slots of this capability now have a verified durable choice.
        quarantinedSlots.removeAll { (subject, _) -> subject == capability }
        gate.replace(effectivePolicy())
        if (quarantinedSlots.isEmpty()) storageFailure = false
        publishSnapshot()
        true
    }

    /**
     * Rebinds adapter descriptors after the legacy connection registry loads
     * (or adds/removes a server). Never changes persisted user choices.
     *
     * Missing descriptors remain ineligible; a saved disabled instance cannot
     * become active merely because it was discovered by SourceRegistry.
     */
    suspend fun refreshDescriptors(next: List<ProviderDescriptor>) = lock.withLock {
        // Validate the replacement before touching the active descriptor set.
        ProviderCapabilityPolicy(next)
        if (descriptors == next) return@withLock
        descriptors = next.toList()
        gate.replace(effectivePolicy())
        publishSnapshot()
    }

    /**
     * Update live operational health for a provider instance without mutating
     * persisted user choices. UNAVAILABLE suspends dispatch immediately.
     */
    suspend fun reportHealth(
        key: ProviderKey,
        status: ProviderOperationalHealth,
    ) = lock.withLock {
        val previous = health[key] ?: ProviderOperationalHealth.UNKNOWN
        if (previous == status) return@withLock
        if (status == ProviderOperationalHealth.UNKNOWN) health.remove(key)
        else health[key] = status
        gate.replace(effectivePolicy())
        publishSnapshot()
    }

    suspend fun statusOf(
        capability: ProviderCapability,
        key: ProviderKey,
    ): ProviderCapabilityStatus = lock.withLock {
        effectivePolicy().status(capability, key, (capability to key) in quarantinedSlots)
    }

    private fun failClosed(storageError: Boolean) {
        initialized = false
        storageFailure = storageError
        choices = emptyMap()
        quarantinedSlots.clear()
        gate.replace(ProviderCapabilityPolicy(descriptors, health = health))
        publishSnapshot()
    }
}
