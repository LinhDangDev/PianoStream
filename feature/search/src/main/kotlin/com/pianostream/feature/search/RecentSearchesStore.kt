package com.pianostream.feature.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Recent searches — an ordered list, newest first, capped at [MAX_ENTRIES];
 * recording an existing entry moves it back to the front (dedupe).
 *
 * Backend is the framework's own named store: `SharedPreferences` under
 * `pianostream.search`. The `androidx.datastore` artifact is NOT on this
 * module's compile classpath (it is an `implementation` dependency of
 * `:core:data`, not `api`) and Gradle files are frozen, so the spec's
 * `preferencesDataStore("pianostream.search")` cannot be reached from here
 * standalone. The API is suspend-shaped so the integrator can swap the
 * backend for a `preferencesDataStore` delegate without touching callers
 * (documented under INTEGRATION in the feature report).
 */
@Singleton
class RecentSearchesStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(load())
    /** Newest-first queries the user actually ran (blank input never stored). */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Moves [query] to the front; empty/whitespace input is ignored. */
    suspend fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        save(listOf(q) + (_recent.value - q))
    }

    suspend fun remove(query: String) {
        save(_recent.value - query)
    }

    suspend fun clear() {
        save(emptyList())
    }

    private fun save(list: List<String>) {
        val capped = list.take(MAX_ENTRIES)
        prefs.edit()
            .putString(KEY_RECENT, Json.encodeToString(STRING_LIST, capped))
            .apply()
        _recent.value = capped
    }

    private fun load(): List<String> =
        runCatching {
            Json.decodeFromString(
                STRING_LIST,
                prefs.getString(KEY_RECENT, null) ?: return emptyList(),
            )
        }.getOrElse { emptyList() }

    companion object {
        const val STORE_NAME: String = "pianostream.search"
        const val MAX_ENTRIES: Int = 10
        private const val KEY_RECENT: String = "recentQueries"
        private val STRING_LIST = ListSerializer(String.serializer())
    }
}
