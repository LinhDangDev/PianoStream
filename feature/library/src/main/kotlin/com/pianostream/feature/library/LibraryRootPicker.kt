package com.pianostream.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.pianostream.core.local.saf.SafPermissions
import kotlinx.coroutines.launch

/**
 * SAF tree picker glue (F8): launches `ACTION_OPEN_DOCUMENT_TREE` built by
 * [SafPermissions.buildPickerIntent] — READ + PERSISTABLE only (the stock
 * `OpenDocumentTree` contract injects WRITE and is therefore never used);
 * persistence uses the flags actually returned; non-allow-listed providers
 * map to `PickerResult.UnsupportedProvider`.
 */
@Composable
fun rememberLibraryRootPicker(
    viewModel: LibraryViewModel,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        scope.launch {
            val data = result.data
            val pick = if (data?.data == null) {
                LibraryViewModel.PickerResult.Cancelled
            } else {
                when (val take = SafPermissions.takePersisted(
                    context.contentResolver, data,
                )) {
                    is SafPermissions.TakeResult.Granted ->
                        LibraryViewModel.PickerResult.Granted(take.treeUri.toString())
                    is SafPermissions.TakeResult.UnsupportedProvider ->
                        LibraryViewModel.PickerResult.UnsupportedProvider
                    SafPermissions.TakeResult.NotPersistable ->
                        LibraryViewModel.PickerResult.UnsupportedProvider
                }
            }
            viewModel.onTreePicked(pick)
        }
    }
    return { launcher.launch(SafPermissions.buildPickerIntent()) }
}
