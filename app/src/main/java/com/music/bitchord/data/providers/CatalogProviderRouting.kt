package com.music.bitchord.data.providers

/**
 * Capability-local ordering, not an identity resolver. The engine may register
 * more providers than a particular catalog surface knows how to render; this
 * boundary dispatches only implemented adapters without promoting any vendor
 * identifier to a synthetic canonical identity.
 */
internal fun orderedCatalogProviders(
    gate: ProviderPolicyGate?,
    capability: ProviderCapability,
    supported: Set<ProviderKey>,
): List<ProviderKey> =
    gate?.ordered(capability)
        ?.map(ProviderDescriptor::key)
        ?.filter { it in supported }
        .orEmpty()
