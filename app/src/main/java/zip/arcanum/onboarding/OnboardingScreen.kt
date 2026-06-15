package zip.arcanum.onboarding

import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.launch
import zip.arcanum.R

private data class OnboardingPage(
    val titleRes: Int,
    val descriptionRes: Int,
    @RawRes val lottieRes: Int
)

private val pages = listOf(
    OnboardingPage(R.string.onboarding_welcome_title,    R.string.onboarding_welcome_desc,    R.raw.hello_wave),
    OnboardingPage(R.string.onboarding_secure_title,     R.string.onboarding_secure_desc,     R.raw.vault),
    OnboardingPage(R.string.onboarding_gallery_title,    R.string.onboarding_gallery_desc,    R.raw.gallery),
    OnboardingPage(R.string.onboarding_calculator_title, R.string.onboarding_calculator_desc, R.raw.calculator)
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope      = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state             = pagerState,
            modifier          = Modifier.fillMaxSize(),
            userScrollEnabled = false
        ) { page ->
            OnboardingPageContent(page = pages[page])
        }

        Column(
            modifier            = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Animated pill indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                pages.indices.forEach { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue   = if (isSelected) 24.dp else 8.dp,
                        animationSpec = tween(300),
                        label         = "indicator_width"
                    )
                    val dotColor by animateColorAsState(
                        targetValue   = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        animationSpec = tween(300),
                        label         = "indicator_color"
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(RoundedCornerShape(99.dp))
                            .background(dotColor)
                    )
                }
            }

            val isLastPage = pagerState.currentPage == pages.size - 1

            Button(
                onClick  = {
                    if (isLastPage) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(99.dp)
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text       = if (isLastPage) stringResource(R.string.onboarding_get_started)
                                     else stringResource(R.string.onboarding_next),
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!isLastPage) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(page.lottieRes))
    val progress    by animateLottieCompositionAsState(
        composition = composition,
        iterations  = LottieConstants.IterateForever
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier
                .fillMaxWidth(0.75f)
                .aspectRatio(1f)
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text       = stringResource(page.titleRes),
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center,
            color      = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text      = stringResource(page.descriptionRes),
            style     = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color     = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
