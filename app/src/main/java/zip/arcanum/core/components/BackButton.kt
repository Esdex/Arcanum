package zip.arcanum.core.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import zip.arcanum.R

/**
 * The way back, everywhere in the app: the arrow inside a filled circle, after the shape
 * Android's own settings use.
 *
 * One component rather than an `IconButton` per screen so the shape cannot drift, and so a
 * change of mind about it is one edit. [overMedia] is for the two screens that sit on top of
 * a photo - there the circle is a dark translucent one and the arrow white, because a tonal
 * colour from the theme disappears against a picture.
 *
 * [enabled] is for the screens that must not be left in the middle of what they are doing -
 * writing a header, say.
 *
 * This is for leaving a screen. Moving up a folder, clearing a selection or stepping back
 * inside a sheet keep their plain icon buttons: they look like the arrow but they are not
 * the way out, and giving them the same weight would say they are.
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    overMedia: Boolean = false,
    contentDescription: String? = null
) {
    val colors = if (overMedia) {
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.35f),
            contentColor   = Color.White
        )
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }

    FilledTonalIconButton(
        onClick  = onClick,
        modifier = modifier,
        enabled  = enabled,
        colors   = colors
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription ?: stringResource(R.string.common_back)
        )
    }
}
