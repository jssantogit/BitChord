package com.music.bitchord.data.catalog.provider.opensubsonic

/**
 * Reuses OpenSubsonicProvider's canonical provider-key rule without constructing
 * its Ktor transport. This keeps availability/configuration reads network-free and
 * prevents a second provider-key definition from drifting from the real adapter.
 */
internal fun openSubsonicProviderKey(config: OpenSubsonicAccountConfig): String =
    OpenSubsonicProvider(
        config = config,
        transport = ProviderKeyOnlyOpenSubsonicTransport,
    ).providerKey

/** Test/fixture convenience that still delegates to the real provider-key rule. */
internal fun openSubsonicProviderKey(accountId: String): String =
    openSubsonicProviderKey(
        OpenSubsonicAccountConfig(
            accountId = accountId,
            serverUrl = "http://localhost",
            auth = OpenSubsonicAuth.ApiKey("provider-key-only"),
        ),
    )

private object ProviderKeyOnlyOpenSubsonicTransport : OpenSubsonicTransport {
    override suspend fun get(endpoint: String, parameters: Map<String, String>): String =
        error("Provider-key resolution must not perform network I/O")
}
