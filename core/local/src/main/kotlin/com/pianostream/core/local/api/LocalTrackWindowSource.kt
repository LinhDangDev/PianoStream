package com.pianostream.core.local.api

/**
 * Expands `pianostream://local/<contentKey>` identities into playable windows
 * (Interface Freeze — CC1, CC9, CC11, B1; Phase 3 implements
 * `LocalTrackWindowSourceImpl` against `library.db` rows).
 *
 * `contentKey` is a stable hash of `(folderPath, discRelativeIndex)` — never a
 * Room rowid — so identity survives `clearLibrary()` + rescan (B3). The source
 * resolves everything through `discs`/`tracks`; nothing trusts a URI path.
 */
public interface LocalTrackWindowSource {

    /**
     * Resolves `contentKeys` to windows. **Missing-key semantics (B1):**
     * unknown keys are omitted from [LocalWindowsResult.windows] AND counted in
     * [LocalWindowsResult.missingCount] — the result is never silently
     * shortened — so the post-scan reconcile can tombstone dangling
     * `playback.db` rows `missing=true` and Phase 4 can surface "N favourites
     * no longer in your library".
     */
    public suspend fun windows(contentKeys: List<String>): LocalWindowsResult
}

/** Result of [LocalTrackWindowSource.windows]. */
public data class LocalWindowsResult(
    val windows: List<LocalTrackWindow>,
    /** Count of requested `contentKey`s that resolved to no live track row (B1). */
    val missingCount: Int,
)
