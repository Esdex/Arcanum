package zip.arcanum.core.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Animated empty-state placeholder. Pass a raw Lottie JSON resource ID via [lottieRes],
 * or null to show just the text (useful before Lottie assets are added).
 */
@Composable
fun EmptyStateView(
    title: String,
    modifier: Modifier = Modifier,
    lottieRes: Int? = null,
    subtitle: String? = null
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier            = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lottieRes != null) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(lottieRes))
                val progress    by animateLottieCompositionAsState(
                    composition = composition,
                    iterations  = LottieConstants.IterateForever,
                    speed       = 0.8f
                )
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(200.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color      = MaterialTheme.colorScheme.onSurface,
                textAlign  = TextAlign.Center
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = subtitle,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}
