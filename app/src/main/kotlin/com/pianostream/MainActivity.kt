package com.pianostream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

/**
 * Phase-1a placeholder activity so `assembleGithubStubDebug` is green on an
 * otherwise empty app tree. Phase 4 owns `app/src/main/`**` and replaces this
 * with the real application UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Text("PianoStream")
            }
        }
    }
}
