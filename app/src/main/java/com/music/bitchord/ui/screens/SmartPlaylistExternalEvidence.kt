package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRecord
import com.music.bitchord.data.listening.SmartPlaylistRule
import com.music.bitchord.data.listening.SmartPlaylistRuleField
import com.music.bitchord.data.listening.SmartPlaylistV2Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Only account identity / active rating scope changes affect source eligibility.
 * Health/sync-status writes must not trigger a costly full-catalog preview read.
 *
 * Do not log or persist keys, credentials, server URLs, or account identifiers.
 */
private data class PreviewAccountScope(
    val accountId: String,
    val serverUrl: String,
    val authMode: String,
    val username: String,
    val ratingScopeId: String,
)

internal fun smartPlaylistAccountChanges(
    accounts: Flow<OpenSubsonicAccountRecord?>,
): Flow<Unit> = accounts
    .map { account ->
        account?.let {
            PreviewAccountScope(
                accountId = it.accountId,
                serverUrl = it.serverUrl,
                authMode = it.authMode.name,
                username = it.username,
                ratingScopeId = it.ratingScopeId,
            )
        }
    }
    .distinctUntilChanged()
    .drop(1) // Freshly opened preview was already evaluated with the current source.
    .map { Unit }

/** This AST visitor inspects nested ALL/ANY/NOT without depending on provider data. */
internal fun SmartPlaylistV2Query.requiresRelativeAgeRefresh(): Boolean {
    fun visit(node: SmartPlaylistRule): Boolean = when (node) {
        is SmartPlaylistRule.Comparison ->
            node.field == SmartPlaylistRuleField.DAYS_SINCE_LAST_AUDIBLE ||
                node.field == SmartPlaylistRuleField.DAYS_SINCE_LOCAL_RATING
        is SmartPlaylistRule.Group -> node.children.any(::visit)
        is SmartPlaylistRule.Not -> visit(node.child)
    }
    return visit(rules)
}

/**
 * Foreground-only fallback for age predicates which change with elapsed time
 * even without an event/Room write. A 15-minute cadence bounds battery/DB
 * overhead while the preview is visible; Play/Queue still revalidate on tap.
 * This is not an exact-timestamp alarm and does not run in the background.
 */
internal fun smartPlaylistRelativeAgeChanges(
    query: SmartPlaylistV2Query,
    intervalMs: Long = 15L * 60_000L,
): Flow<Unit> {
    require(intervalMs > 0L)
    if (!query.requiresRelativeAgeRefresh()) return emptyFlow()
    return flow {
        while (true) {
            delay(intervalMs)
            emit(Unit)
        }
    }
}
