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
    private val savedAccount = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(appContext),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(appContext),
    )

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
        OpenSubsonicCatalogSync.sync(
            appContext, provider,
            accountIsCurrent = { provider.matchesSavedConfig(savedAccount.savedConfig()) },
        )
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
        // Capture before the credential read: even an A -> B -> A edit must
        // invalidate results from a suspended connection test.
        val revision = OpenSubsonicAccountRevisionClock.current()
        val provider = repository.savedProvider()
            ?: return Result.failure(IllegalStateException("OpenSubsonic account is not configured"))
        return testProvider(provider, persistHealth = true, accountRevision = revision)
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
        // A user may change URL/auth or forget and recreate an account while
        // this network operation was suspended. Do not mutate the NEW account's
        // status with a result from the old configuration.
        if (!provider.matchesSavedConfig(repository.savedConfig())) {
            return ProviderSyncResult.Failure("OpenSubsonic account changed during sync")
        }
        repository.recordSync(result, nowEpochSeconds())
        return result
    }

    private suspend fun testProvider(
        provider: OpenSubsonicProvider,
        persistHealth: Boolean,
        accountRevision: Long? = null,
    ): Result<OpenSubsonicConnectionReport> = try {
        val report = operations.test(provider)
        when {
            !persistHealth -> Result.success(report)
            !savedAccountIsCurrent(provider, accountRevision) ->
                Result.failure(IllegalStateException("OpenSubsonic account changed during connection test"))
            else -> {
                repository.recordHealth(report.health, nowEpochSeconds())
                Result.success(report)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A failed probe from an obsolete account must not mark the newly
        // configured account unreachable. Never publish sensitive exception text.
        if (persistHealth && savedAccountIsCurrent(provider, accountRevision)) {
            repository.recordHealthFailure(nowEpochSeconds())
        }
        Result.failure(IllegalStateException("OpenSubsonic connection failed"))
    }

    private fun savedAccountIsCurrent(
        provider: OpenSubsonicProvider,
        capturedRevision: Long?,
    ): Boolean {
        if (capturedRevision == null ||
            OpenSubsonicAccountRevisionClock.current() != capturedRevision
        ) return false
        val matches = runCatching {
            provider.matchesSavedConfig(repository.savedConfig())
        }.getOrDefault(false)
        return matches && OpenSubsonicAccountRevisionClock.current() == capturedRevision
    }
}
