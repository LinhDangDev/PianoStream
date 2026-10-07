package com.pianostream.app.settings

import android.content.Context
import android.net.Uri
import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.data.settings.SettingsDataStore
import com.pianostream.core.local.saf.SafPermissions
import com.pianostream.core.youtube.di.YouTubeLocalWipe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Canonical DELETE ALL LOCAL DATA (CC10/F19). Order is fixed:
 *  1. release the persisted SAF grant (F8) — while the resolver is usable;
 *  2. wipe `playback.db` + `library.db` (repos close their own -wal/-shm);
 *  3. clear the settings DataStore;
 *  4. drop caches (`cacheDir/media3` stream cache, `cacheDir/coil`);
 *  5. drop in-memory YouTube singletons + `filesDir/player_configs.json`
 *     via [YouTubeLocalWipe] (never touches `filesDir/potoken/` — nothing is
 *     persisted there, R2/CC10).
 */
@Singleton
public class LocalDataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackRepository,
    private val library: LibraryRepository,
    private val settings: SettingsDataStore,
    private val youtubeWipe: YouTubeLocalWipe,
) {

    public suspend fun wipeAll() = withContext(Dispatchers.IO) {
        // 1. SAF grant.
        runCatching {
            val tree = settings.libraryRootTreeUri.first()
            if (tree != null) {
                SafPermissions.revoke(context.contentResolver, Uri.parse(tree))
            }
        }
        // 2. Both Room databases.
        runCatching { playback.clearAll() }
        runCatching { library.clearAll() }
        // 3. DataStore.
        runCatching { settings.clear() }
        // 4. Caches.
        listOf(
            File(context.cacheDir, "media3"),
            File(context.cacheDir, "coil"),
            File(context.filesDir, "player_configs.json"),
        ).forEach { f ->
            runCatching {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
        // 5. In-memory YouTube singletons + player_configs.json.
        runCatching { youtubeWipe.wipe() }
    }
}
