package com.pianostream.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.pianostream.core.media.api.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped host ViewModel: owns the ONE app-scoped controller
 * connection (plan C1) so the mini player in [AppScaffold] and every
 * destination drive the same `MediaController` via `PlaybackController`.
 */
@HiltViewModel
public class AppViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    private val _player = MutableStateFlow<Player?>(null)
    public val player: StateFlow<Player?> = _player.asStateFlow()

    public fun connectIfNeeded() {
        if (_player.value != null) return
        viewModelScope.launch {
            if (runCatching { controller.connect() }.getOrDefault(false)) {
                _player.value = controller.player
            }
        }
    }
}
