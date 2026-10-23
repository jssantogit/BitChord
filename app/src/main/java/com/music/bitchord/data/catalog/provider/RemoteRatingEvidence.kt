package com.music.bitchord.data.catalog.provider

/** Provider evidence is never a first-party Listening rating. */
internal enum class RemoteNumericRatingKind { NOT_PROVIDED, CLEARED_ZERO, VALUE, INVALID }

internal data class RemoteNumericRating(
    val kind: RemoteNumericRatingKind = RemoteNumericRatingKind.NOT_PROVIDED,
    val value: Int? = null,
) {
    init {
        require(if (kind == RemoteNumericRatingKind.VALUE) value != null && value in 1..5 else value == null) {
            "Remote numeric values must be integral 1..5, or unvalued evidence"
        }
    }
}

internal enum class RemoteStarredKind { NOT_PROVIDED, NOT_STARRED, STARRED, INVALID }

internal data class ProviderRemoteRating(
    val numeric: RemoteNumericRating = RemoteNumericRating(),
    val starred: RemoteStarredKind = RemoteStarredKind.NOT_PROVIDED,
)
