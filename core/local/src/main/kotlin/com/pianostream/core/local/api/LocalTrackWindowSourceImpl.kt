package com.pianostream.core.local.api

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frozen `LocalTrackWindowSource` over `library.db` (CC9/CC11/B1), resolved
 * through [LocalLibraryIndex]. Unknown keys are omitted and counted in
 * `missingCount` (B1).
 */
@Singleton
class LocalTrackWindowSourceImpl @Inject constructor(
    private val index: LocalLibraryIndex,
) : LocalTrackWindowSource {

    override suspend fun windows(contentKeys: List<String>): LocalWindowsResult {
        if (contentKeys.isEmpty()) return LocalWindowsResult(emptyList(), 0)
        val wanted = contentKeys.toSet()
        val byKey = index.index()
        val out = ArrayList<LocalTrackWindow>(contentKeys.size)
        var matched = 0
        for (key in contentKeys) {
            val e = byKey[key] ?: continue
            matched++
            out += LocalTrackWindow(
                contentKey = key,
                flacUri = e.flacUri,
                startMs = e.startMs,
                endMs = e.endMs,
                gainDb = e.gainDb,
                peak = e.peak,
            )
        }
        return LocalWindowsResult(out, wanted.size - matched)
    }
}
