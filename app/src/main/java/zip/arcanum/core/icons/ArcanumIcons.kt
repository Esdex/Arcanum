package zip.arcanum.core.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ArcanumIcons {

    val KeyfileFilled: ImageVector by lazy {
        ImageVector.Builder(
            name           = "Arcanum.KeyfileFilled",
            defaultWidth   = 24.dp,
            defaultHeight  = 24.dp,
            viewportWidth  = 24f,
            viewportHeight = 24f
        ).apply {
            // Document body with key shape cut out via EvenOdd.
            // Works correctly with any tint color: doc becomes tint, key remains transparent.
            path(
                fill            = SolidColor(Color.White),
                stroke          = null,
                pathFillType    = PathFillType.EvenOdd
            ) {
                // Document outline with folded top-right corner
                moveTo(4f, 2f)
                lineTo(15f, 2f)
                lineTo(20f, 7f)
                lineTo(20f, 22f)
                lineTo(4f, 22f)
                close()
                // Key bow cutout: circle centered at (9, 16) r=2
                moveTo(7f, 16f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 11f, 16f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 7f, 16f)
                close()
                // Key shaft cutout (horizontal rectangle)
                moveTo(11f, 15.2f)
                lineTo(17.5f, 15.2f)
                lineTo(17.5f, 16.8f)
                lineTo(11f, 16.8f)
                close()
                // Tooth 1 cutout
                moveTo(13.1f, 16.8f)
                lineTo(14.1f, 16.8f)
                lineTo(14.1f, 18.5f)
                lineTo(13.1f, 18.5f)
                close()
                // Tooth 2 cutout
                moveTo(15.1f, 16.8f)
                lineTo(16.1f, 16.8f)
                lineTo(16.1f, 18f)
                lineTo(15.1f, 18f)
                close()
            }
        }.build()
    }

    val Keyfile: ImageVector by lazy {
        ImageVector.Builder(
            name           = "Arcanum.Keyfile",
            defaultWidth   = 24.dp,
            defaultHeight  = 24.dp,
            viewportWidth  = 24f,
            viewportHeight = 24f
        ).apply {
            val stroke = SolidColor(Color.White)

            // Document body outline with folded top-right corner
            path(
                fill            = null,
                stroke          = stroke,
                strokeLineWidth = 1.5f,
                strokeLineCap   = StrokeCap.Round,
                strokeLineJoin  = StrokeJoin.Round
            ) {
                moveTo(4f, 2f)
                lineTo(15f, 2f)
                lineTo(20f, 7f)
                lineTo(20f, 22f)
                lineTo(4f, 22f)
                close()
            }
            // Corner fold crease
            path(
                fill            = null,
                stroke          = stroke,
                strokeLineWidth = 1.5f,
                strokeLineCap   = StrokeCap.Round,
                strokeLineJoin  = StrokeJoin.Round
            ) {
                moveTo(15f, 2f)
                lineTo(15f, 7f)
                lineTo(20f, 7f)
            }
            // Key bow: full circle, center (9,16) radius 2
            path(
                fill            = null,
                stroke          = stroke,
                strokeLineWidth = 1.5f
            ) {
                moveTo(7f, 16f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 11f, 16f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 7f, 16f)
                close()
            }
            // Key shaft: from right edge of bow to far right
            path(
                fill            = null,
                stroke          = stroke,
                strokeLineWidth = 1.5f,
                strokeLineCap   = StrokeCap.Round
            ) {
                moveTo(11f, 16f)
                lineTo(17f, 16f)
            }
            // Key teeth: two downward notches on the shaft
            path(
                fill            = null,
                stroke          = stroke,
                strokeLineWidth = 1.5f,
                strokeLineCap   = StrokeCap.Round
            ) {
                moveTo(13.5f, 16f)
                lineTo(13.5f, 18f)
                moveTo(15.5f, 16f)
                lineTo(15.5f, 17.5f)
            }
        }.build()
    }

}
