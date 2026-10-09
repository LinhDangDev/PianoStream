package com.pianostream.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Library-surface preferences (v3 feature set) — separate store from
 * `pianostream.settings` so the Settings wipe story stays untouched.
 *
 * Lives in `:core:data` because `datastore-preferences` is an
 * `implementation` dep of this module only — feature modules cannot see
 * the DataStore types on their compile classpath.
 */
public class LibraryPrefsStore internal constructor(
    private val store: DataStore<Preferences>,
) {

    /** Discs tab ordering (persisted under `discsSort`). */
    public enum class DiscsSort { RECENT, TITLE_AZ, ARTIST }

    /** Discs tab layout (persisted under `discsView`). */
    public enum class DiscsView { LIST, GRID }

    public companion object {
        public const val STORE_NAME: String = "pianostream.library"

        public const val DEFAULT_DISCS_SORT: String = "RECENT"
        public const val DEFAULT_DISCS_VIEW: String = "LIST"

        private val KEY_DISCS_SORT = stringPreferencesKey("discsSort")
        private val KEY_DISCS_VIEW = stringPreferencesKey("discsView")

        private val Context.libraryPrefsStore by preferencesDataStore(STORE_NAME)

        /** The application-wide store. */
        public fun default(context: Context): LibraryPrefsStore =
            LibraryPrefsStore(context.libraryPrefsStore)
    }

    /**
     * `discsSort(RECENT|TITLE_AZ|ARTIST)`, default RECENT (the scan order the
     * tab already showed). Field mapping lives with the sort consumer:
     * RECENT → `Disc.scannedAt` desc, TITLE_AZ → `Disc.title`, ARTIST →
     * `Disc.performer ?: Disc.composer`.
     */
    public val discsSort: Flow<DiscsSort> =
        store.data.map { prefs ->
            prefs[KEY_DISCS_SORT]
                ?.let { runCatching { DiscsSort.valueOf(it) }.getOrNull() }
                ?: DiscsSort.valueOf(DEFAULT_DISCS_SORT)
        }

    public suspend fun setDiscsSort(sort: DiscsSort) {
        store.edit { it[KEY_DISCS_SORT] = sort.name }
    }

    /** `discsView(LIST|GRID)`, default LIST (the long-standing row look). */
    public val discsView: Flow<DiscsView> =
        store.data.map { prefs ->
            prefs[KEY_DISCS_VIEW]
                ?.let { runCatching { DiscsView.valueOf(it) }.getOrNull() }
                ?: DiscsView.valueOf(DEFAULT_DISCS_VIEW)
        }

    public suspend fun setDiscsView(view: DiscsView) {
        store.edit { it[KEY_DISCS_VIEW] = view.name }
    }
}
