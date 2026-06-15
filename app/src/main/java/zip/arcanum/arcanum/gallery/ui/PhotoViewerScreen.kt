package zip.arcanum.arcanum.gallery.ui

import androidx.compose.runtime.Composable

// Superseded by MediaViewerScreen — kept for backwards-compat if referenced elsewhere.
@Composable
fun PhotoViewerScreen(
    photoId: String,
    onBack: () -> Unit = {}
) {
    MediaViewerScreen(photoId = photoId, onBack = onBack)
}
