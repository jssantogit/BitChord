package com.music.bitchord.acquisition.a0

import java.util.concurrent.ConcurrentHashMap

/** Search-specific ticket registry: only dispatched queries may produce replies.
 * Single search driver adds tickets, peer reader threads inspect membership.
 * Never persisted, exposed to UI, or logged.
 */
internal class A0SearchTicketRegistry(initial:Int) {
    private val registered=ConcurrentHashMap.newKeySet<Int>()
    init { require(initial>0); registered.add(initial) }
    val accepted:Set<Int> get()=registered
    fun register(token:Int):Boolean =
        token>0 && registered.size<3 && registered.add(token)
}
