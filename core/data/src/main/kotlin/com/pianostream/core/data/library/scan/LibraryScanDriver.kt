package com.pianostream.core.data.library.scan

import com.pianostream.core.data.library.model.ScanProgress
import com.pianostream.core.data.library.model.ScanResult

/**
 * Scan entry seam — `core:data` owns the DBs but not the SAF/cue logic;
 * `core:local` supplies the implementation (Phase-3 module graph is
 * `local → data`, so the driver is injected, never called by class).
 * [run] performs the full scan + write + B1 reconcile and returns the
 * terminal result; `grantLost` aborts leaving previous rows intact (M2).
 */
interface LibraryScanDriver {
    suspend fun run(
        treeUri: String,
        onProgress: suspend (ScanProgress) -> Unit,
    ): ScanResult
}
