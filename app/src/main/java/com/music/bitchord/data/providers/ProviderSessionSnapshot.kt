package com.music.bitchord.data.providers

/**
 * Headless status contract for provider settings and diagnostics.
 *
 * This does not render UI or hold secrets. It intentionally separates user
 * selections (what was durably configured) from currently admitted sources
 * (what is READY, enabled, operationally available and not quarantined).
 */
enum class ProviderSessionStatus {
    BOOTING,
    READY,
    PERSISTING,
    STORAGE_ERROR,
}

data class ProviderSessionSnapshot(
    val status: ProviderSessionStatus = ProviderSessionStatus.BOOTING,
    val configured: Map<ProviderCapability, List<ProviderChoice>> = emptyMap(),
    val eligible: Map<ProviderCapability, List<ProviderKey>> = emptyMap(),
    val quarantined: Set<ProviderCapability> = emptySet(),
    val quarantinedProviders: Set<Pair<ProviderCapability, ProviderKey>> = emptySet(),
    val health: Map<ProviderKey, ProviderOperationalHealth> = emptyMap(),
)
