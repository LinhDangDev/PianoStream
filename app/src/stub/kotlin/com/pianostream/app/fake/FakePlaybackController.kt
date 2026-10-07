package com.pianostream.app.fake

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.pianostream.core.media.api.PlaybackController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory `PlaybackController` bound to [FakePlayer] — no service (stub). */
@UnstableApi
@Singleton
public class FakePlaybackController @Inject constructor() : PlaybackController {

    private val fakePlayer = FakePlayer()

    override val player: Player get() = fakePlayer

    private val ready = MutableStateFlow(false)

    override val isPlaylistReady: Flow<Boolean> = ready

    override suspend fun connect(): Boolean {
        ready.value = true
        return true
    }

    override fun release() {
        ready.value = false
    }
}
