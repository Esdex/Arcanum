package zip.arcanum.arcanum.gallery.domain

import android.graphics.Bitmap

data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artwork: Bitmap?
)
