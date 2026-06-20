package zip.arcanum.core.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R

@Composable
fun UpgradeOverlay(
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit = onDismiss
) {
    BackHandler(enabled = true) { onDismiss() }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.star))
    val progress    by animateLottieCompositionAsState(
        composition = composition,
        iterations  = LottieConstants.IterateForever
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Column(
                    modifier            = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress    = { progress },
                        modifier    = Modifier.size(320.dp)
                    )

                    Column(
                        modifier            = Modifier.offset(y = (-80).dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text       = stringResource(R.string.upgrade_dialog_title),
                        style      = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        color      = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text      = stringResource(R.string.upgrade_dialog_subtitle),
                        style     = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))

                    listOf(
                        R.string.upgrade_dialog_feature_containers,
                        R.string.upgrade_dialog_feature_amoled,
                        R.string.upgrade_dialog_feature_support
                    ).forEach { res ->
                        Text(
                            text      = "• ${stringResource(res)}",
                            style     = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text       = stringResource(R.string.upgrade_dialog_price),
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign  = TextAlign.Center,
                        color      = MaterialTheme.colorScheme.primary
                    )

                    } // offset column
                }

                Column(
                    modifier            = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick  = onUpgrade,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = CircleShape
                    ) {
                        Text(
                            text       = stringResource(R.string.upgrade_overlay_cta),
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    TextButton(
                        onClick  = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text  = stringResource(R.string.upgrade_dialog_later),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
