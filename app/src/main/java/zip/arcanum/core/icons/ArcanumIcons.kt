package zip.arcanum.core.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
object ArcanumIcons {

    val Encrypted: ImageVector by lazy {
        ImageVector.Builder(
            name           = "Arcanum.Encrypted",
            defaultWidth   = 24.dp,
            defaultHeight  = 24.dp,
            viewportWidth  = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(10.5f, 15f)
                horizontalLineToRelative(3f)
                lineTo(12.93f, 11.77f)
                quadToRelative(0.5f, -0.25f, 0.79f, -0.72f)
                reflectiveQuadTo(14f, 10f)
                quadTo(14f, 9.17f, 13.41f, 8.59f)
                reflectiveQuadTo(12f, 8f)
                reflectiveQuadTo(10.59f, 8.59f)
                reflectiveQuadTo(10f, 10f)
                quadToRelative(0f, 0.57f, 0.29f, 1.05f)
                reflectiveQuadToRelative(0.79f, 0.72f)
                lineTo(10.5f, 15f)
                close()
                moveTo(12f, 22f)
                quadTo(8.53f, 21.13f, 6.26f, 18.01f)
                reflectiveQuadTo(4f, 11.1f)
                verticalLineTo(5f)
                lineTo(12f, 2f)
                lineToRelative(8f, 3f)
                verticalLineToRelative(6.1f)
                quadToRelative(0f, 3.8f, -2.26f, 6.91f)
                reflectiveQuadTo(12f, 22f)
                close()
                moveToRelative(0f, -2.1f)
                quadToRelative(2.6f, -0.82f, 4.3f, -3.3f)
                reflectiveQuadTo(18f, 11.1f)
                verticalLineTo(6.38f)
                lineTo(12f, 4.13f)
                lineTo(6f, 6.38f)
                verticalLineTo(11.1f)
                quadToRelative(0f, 3.03f, 1.7f, 5.5f)
                reflectiveQuadTo(12f, 19.9f)
                close()
                moveTo(12f, 12f)
                close()
            }
        }.build()
    }

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
