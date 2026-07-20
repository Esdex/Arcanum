package zip.arcanum.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R

/**
 * The "this is running, wait" screen: looping animation over a title.
 *
 * The counterpart to [OperationSuccess], and it exists for the same reason -
 * several screens had their own copy and they had drifted. Backup and restore
 * header were still on a bare CircularProgressIndicator with a dimmed
 * bodyMedium label, which made a long operation look like a stalled one next to
 * the animated screens everywhere else.
 *
 * [subtitle] is for the detail under the title, such as a warning not to close
 * the app.
 */
@Composable
fun OperationLoading(
    title: String,
    subtitle: String? = null
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LottieAnimation(
                composition = composition,
                iterations  = LottieConstants.IterateForever,
                modifier    = Modifier.size(160.dp)
            )
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign  = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text      = subtitle,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
