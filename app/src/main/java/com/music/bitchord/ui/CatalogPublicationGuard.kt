package com.music.bitchord.ui

import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderRequestTicket

/**
 * Publication is the last capability gate, separate from HTTP admission.
 * Revocation, query changes and source visibility are checked together.
 * It applies equally to any provider/instance and never resolves identity.
 */
internal fun catalogPublicationAllowed(
    gate: ProviderPolicyGate?,
    ticket: ProviderRequestTicket?,
    correctSource: Boolean,
    correctQuery: Boolean,
    visibleProvider: Boolean,
): Boolean =
    correctSource && correctQuery && visibleProvider &&
        ticket != null && gate?.accept(ticket) == true

/**
 * An authorized ticket is not enough when a user reorders eligible catalog
 * providers: the same ticket may survive a priority-only policy change.
 * Late search pages and image enrichment must use the original routing order,
 * not publish into the new winner's search session.
 *
 * Other capability edits do not invalidate this catalog-only boundary.
 */
internal fun catalogSearchOrderIsCurrent(
    provider: ProviderKey,
    dispatchedOrder: List<ProviderKey>,
    currentOrder: List<ProviderKey>,
): Boolean = provider in dispatchedOrder && dispatchedOrder == currentOrder
