package zip.arcanum.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R

/**
 * The "the operation finished" screen: check animation, title, body, and a
 * full-width button pinned to the bottom.
 *
 * This exists because six screens had grown their own copy of it and drifted
 * apart on every axis - animation size, title style, whether the haptic fired
 * at all, and whether the button sat at the bottom or floated in the middle of
 * a centred column. Backup and restore had ended up with no animation and no
 * haptic. Use this rather than writing a seventh copy.
 *
 * [extra] renders under the body, inside the centred block, for the cases that
 * need to show something specific - the keyfile generator lists the files it
 * created and repeats its backup warning there.
 */
@Composable
fun OperationSuccess(
    title: String,
    body: String? = null,
    onDone: () -> Unit,
    doneLabel: String = stringResource(R.string.common_done),
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Column(
        modifier            = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(180.dp)
                )
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
                if (body != null) {
                    Text(
                        text      = body,
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                extra?.invoke(this)
            }
        }
        Button(
            onClick  = onDone,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Text(doneLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}
