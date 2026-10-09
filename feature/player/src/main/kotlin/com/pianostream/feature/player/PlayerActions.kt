package com.pianostream.feature.player

/**
 * Optional Now Playing actions shown in the "..." sheet on [ExpandingPlayer].
 * A null lambda hides its row, so the sheet only lists what the host wired
 * up — the scaffold layer supplies the real implementations.
 */
data class PlayerActions(
    val onAddToPlaylist: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onGoToAlbum: (() -> Unit)? = null,
    val onGoToArtist: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onSleepTimer: (() -> Unit)? = null,
    /** Download/remove toggle for the current item (YouTube rows only —
     *  the sheet hides it unless the item is videoCapable). */
    val onDownload: (() -> Unit)? = null,
)
