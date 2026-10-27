package com.music.bitchord.acquisition.a0

/**
 * Stable upper-bound insertion into an already ordered, bounded manual list.
 * O(log N) expensive metadata comparisons rather than re-sorting all prior
 * candidates on every peer reply. O(N) element shifts, at most 384 references.
 * Does NOT modify scoring, filtering, admission quotas or transfer policy.
 */
internal object A0StableCandidateInsertion {
    fun <T> add(list:MutableList<T>,value:T,ordering:Comparator<in T>) {
        var low=0
        var high=list.size
        while(low<high) {
            val mid=low+(high-low)/2
            if(ordering.compare(list[mid],value)<=0) low=mid+1
            else high=mid
        }
        list.add(low,value)
    }
}
