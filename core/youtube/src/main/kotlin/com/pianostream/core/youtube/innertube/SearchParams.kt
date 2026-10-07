package com.pianostream.core.youtube.innertube

import com.pianostream.core.youtube.api.SearchFilter

/**
 * Base64 protobuf `params` for `youtubei/v1/search`, per filter tab.
 * Byte layout follows the community-documented YouTube Music params:
 * field 1 (varint filter id) nested in field 2 of the search params message.
 */
internal object SearchParams {

    fun forFilter(filter: SearchFilter): String? = when (filter) {
        SearchFilter.ALL -> null // no params = the "Top results" tab
        SearchFilter.SONGS -> encode(filterId = 2)
        SearchFilter.VIDEOS -> encode(filterId = 3)
        SearchFilter.ALBUMS -> encode(filterId = 5)
        SearchFilter.ARTISTS -> encode(filterId = 4)
        SearchFilter.PLAYLISTS -> encode(filterId = 7)
    }

    /**
     * Protobuf: outer msg field 1 = 1 (varint scope: music), field 2 =
     * embedded { field 1 = filterId } — matches the tab params YT Music emits.
     */
    private fun encode(filterId: Int): String {
        val inner = byteArrayOf(0x08, filterId.toByte())
        val outer = byteArrayOf(
            0x08, 0x01,
            0x12, inner.size.toByte(), *inner,
        )
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(outer)
    }
}
