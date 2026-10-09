package com.pianostream.app.fake

import com.pianostream.core.data.library.LibraryRepository
import com.pianostream.core.data.playback.PlaybackRepository
import com.pianostream.core.media.api.PlaybackController

/**
 * Runtime registry of the live fakes, for instrumentation tests.
 *
 * androidTest code can't reach the app's `SingletonComponent` — the Hilt
 * gradle plugin runs no `@EntryPoint` codegen on test sources — and it must
 * not reference stub-only types directly either (that would break
 * real-flavor test compilation, which shares the androidTest sourceset).
 * So each fake registers itself here and tests read the handles through
 * `Class.forName(...)` reflection; on the real flavor the class simply
 * doesn't exist and the tests skip.
 */
public object StubRuntime {
    @JvmField public var playbackController: PlaybackController? = null

    @JvmField public var playbackRepository: PlaybackRepository? = null

    @JvmField public var libraryRepository: LibraryRepository? = null
}
