package com.music.bitchord.data.catalog.provider.opensubsonic

import android.content.Context
import com.music.bitchord.data.catalog.provider.ProviderCapabilities
import com.music.bitchord.data.catalog.provider.ProviderHealth
import com.music.bitchord.data.catalog.provider.ProviderSyncResult
import kotlinx.coroutines.CancellationException

internal data class OpenSubsonicConnectionReport(
    val health: ProviderHealth,
    val capabilities: ProviderCapabilities?,
)

internal interface OpenSubsonicAccountOperations {
    suspend fun test(provider: OpenSubsonicProvider): OpenSubsonicConnectionReport
    suspend fun sync(provider: OpenSubsonicProvider): ProviderSyncResult
}

internal class AndroidOpenSubsonicAccountOperations(
    context: Context,
) : OpenSubsonicAccountOperations {
    private val appContext = context.applicationContext

    override suspend fun test(provider: OpenSubsonicProvider): OpenSubsonicConnectionReport {
        val health = provider.health()
        if (!health.reachable) throw IllegalStateException("OpenSubsonic server is unreachable")
        val capabilities = try {
            provider.capabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Extension discovery is optional on older Subsonic servers. A good
            // authenticated ping still proves the account itself works.
            null
        }
        return OpenSubsonicConnectionReport(health, capabilities)
    }

    override suspend fun sync(provider: OpenSubsonicProvider): ProviderSyncResult =
        OpenSubsonicCatalogSync.sync(appContext, provider)
}

/** Coordinates UI actions without exposing transport/request exception text. */
internal class OpenSubsonicAccountController(
    private val repository: OpenSubsonicAccountRepository,
    private val operations: OpenSubsonicAccountOperations,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    suspend fun testConnection(
        draft: OpenSubsonicAccountDraft,
    ): Result<OpenSubsonicConnectionReport> {
        val provider = try {
            repository.providerForDraft(draft)
        } catch (validation: IllegalArgumentException) {
            return Result.failure(validation)
        }
        return testProvider(provider, persistHealth = false)
    }

    suspend fun testSavedConnection(): Result<OpenSubsonicConnectionReport> {
        val provider = repository.savedProvider()
            ?: return Result.failure(IllegalStateException("OpenSubsonic account is not configured"))
        return testProvider(provider, persistHealth = true)
    }

    suspend fun syncNow(): ProviderSyncResult {
        val provider = repository.savedProvider()
            ?: return ProviderSyncResult.Failure("OpenSubsonic account is not configured")
        val result = try {
            operations.sync(provider)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Never carry exception text into UI/persisted status; request URLs
            // can contain apiKey or salted-token query parameters.
            ProviderSyncResult.Failure("OpenSubsonic sync failed")
        }
        repository.recordSync(result, nowEpochSeconds())
        return result
    }

    private suspend fun testProvider(
        provider: OpenSubsonicProvider,
        persistHealth: Boolean,
    ): Result<OpenSubsonicConnectionReport> = try {
        val report = operations.test(provider)
        if (persistHealth) repository.recordHealth(report.health, nowEpochSeconds())
        Result.success(report)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        if (persistHealth) repository.recordHealthFailure(nowEpochSeconds())
        Result.failure(IllegalStateException("OpenSubsonic connection failed"))
    }
}
