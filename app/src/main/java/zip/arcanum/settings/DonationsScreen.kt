package zip.arcanum.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.theme.LocalDarkMode

/**
 * One payment route. Links open externally; wallets copy to the clipboard, since
 * the app has no network permission and cannot complete a payment itself (#66).
 */
private sealed interface DonationTarget {
    val labelRes: Int
    val iconRes: Int

    /**
     * Ethereum's official mark is monochrome and ships with no fill of its own, so it
     * is stored white and has to follow the theme - left as-is it disappears against a
     * light background. Every other mark carries its own brand colour and must not be
     * tinted.
     */
    val tinted: Boolean get() = false

    data class Link(
        override val labelRes: Int,
        override val iconRes: Int,
        val descriptionRes: Int,
        val url: String,
    ) : DonationTarget

    data class Wallet(
        override val labelRes: Int,
        override val iconRes: Int,
        val address: String,
        override val tinted: Boolean = false,
    ) : DonationTarget
}

private val LOTTIE_HEIGHT = 240.dp
/** Half of star.json's own whitespace at this size — see the call site. Scales with it. */
private val LOTTIE_GAP_TRIM = 30.25.dp
/**
 * Same idea above the animation, but tuned separately: the gap under the top bar was
 * cut by half and then by a further quarter of what was left.
 */
private val LOTTIE_TOP_TRIM = 37.8.dp
/** Gap wanted below the intro text, before the first card. */
private val INTRO_BOTTOM_GAP = 28.dp

private val DONATION_TARGETS = listOf(
    DonationTarget.Link(
        labelRes       = R.string.donations_github_sponsors,
        iconRes        = R.drawable.ic_coin_sponsors,
        descriptionRes = R.string.donations_github_sponsors_desc,
        url            = "https://github.com/sponsors/Esdex",
    ),
    DonationTarget.Link(
        labelRes       = R.string.donations_kofi,
        iconRes        = R.drawable.ic_coin_kofi,
        descriptionRes = R.string.donations_kofi_desc,
        url            = "https://ko-fi.com/Esdex",
    ),
    DonationTarget.Wallet(
        labelRes = R.string.donations_bitcoin,
        iconRes  = R.drawable.ic_coin_bitcoin,
        address  = "bc1qk3pjpxfzafpc56924m8hnyewcgmutchwrg4v2p",
    ),
    DonationTarget.Wallet(
        labelRes = R.string.donations_lightning,
        iconRes  = R.drawable.ic_coin_lightning,
        address  = "esdex@cake.cash",
    ),
    DonationTarget.Wallet(
        labelRes = R.string.donations_ethereum,
        iconRes  = R.drawable.ic_coin_ethereum,
        address  = "0xDc4B00d937e4a9633d37d70dDF56E8370f44E0f8",
        tinted   = true,
    ),
    DonationTarget.Wallet(
        labelRes = R.string.donations_monero,
        iconRes  = R.drawable.ic_coin_monero,
        address  = "83xHcG9NNzLhsYQ9QoMcX2EFCwEPT1rSSa4EPgDMG3PqQEXVZ1vgaTtAq9x4zETjkRRK7CiH6giHshTLUJHTD4mCRBbt42s",
    ),
    DonationTarget.Wallet(
        labelRes = R.string.donations_solana,
        iconRes  = R.drawable.ic_coin_solana,
        address  = "GJgu5VqmEfxfQQbRpp9CDcYUxjsjUPJpHsCfyjRQGGSX",
    ),
)

@Composable
internal fun DonationsSubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.star))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations  = LottieConstants.IterateForever
    )

    var confettiBurst by remember { mutableIntStateOf(0) }

    SubScreenScaffold(
        title  = stringResource(R.string.donations_title),
        onBack = onBack
    ) { innerPadding ->
      Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier       = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                // star.json is padded on every side, so the space under the top bar is the
                // animation's own margin, same as the gap below it. Trimming the list's top
                // padding by the same amount closes it by half. Done here rather than with
                // offset() so the space is actually reclaimed instead of left behind, and
                // only the empty part of the canvas ends up under the bar.
                top    = (innerPadding.calculateTopPadding() - LOTTIE_TOP_TRIM).coerceAtLeast(0.dp),
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
        ) {
            item {
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier
                        .fillMaxWidth()
                        .height(LOTTIE_HEIGHT)
                        // No ripple: a circular splash on a free-floating animation looks
                        // like a rendering fault rather than a button press.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confettiBurst++
                        }
                )
            }

            item {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        // star.json carries a lot of empty canvas around the artwork, so the
                        // visible gap is the animation's own padding rather than any spacing
                        // we set. UpgradeOverlay pulls its text up by 80dp against a 320dp
                        // animation to cancel it out; half of that, scaled to this size, is
                        // what closes the gap by half here.
                        .offset(y = -LOTTIE_GAP_TRIM)
                        // Reduced by the same amount, since offset() shifts the text without
                        // giving back the space it left behind. Floored at zero: padding may
                        // not go negative, and the trim grows every time the animation does.
                        .padding(bottom = (INTRO_BOTTOM_GAP - LOTTIE_GAP_TRIM).coerceAtLeast(0.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text       = stringResource(R.string.donations_heading),
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text      = stringResource(R.string.donations_body),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            items(DONATION_TARGETS, key = { it.labelRes }) { target ->
              Column(Modifier.fillMaxWidth()) {
                Text(
                    text       = stringResource(target.labelRes).uppercase(),
                    style      = MaterialTheme.typography.labelMedium,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                    modifier   = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                when (target) {
                    is DonationTarget.Link -> DonationCard(
                        icon    = { BrandMark(target) },
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url)))
                            }
                        }
                    ) {
                        Text(
                            text     = stringResource(target.descriptionRes),
                            style    = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    is DonationTarget.Wallet -> DonationCard(
                        icon    = { BrandMark(target) },
                        trailing = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.ContentCopy, null,
                                    modifier = Modifier.size(17.dp),
                                    tint     = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyAddress(context, context.getString(target.labelRes), target.address)
                        }
                    ) {
                        // Truncated on purpose: a Monero address is 95 characters and would
                        // otherwise set the row's height on its own. Tapping copies the whole
                        // thing, so nothing is lost by not showing all of it.
                        Text(
                            text       = target.address,
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
              }
            }
        }

        // Above the list so pieces fall over the cards, but with no pointer input of its
        // own, so taps still reach them.
        ConfettiOverlay(burst = confettiBurst, modifier = Modifier.fillMaxSize())
      }
    }
}

/**
 * Android 13 shows its own confirmation whenever an app writes to the clipboard, so a
 * toast of ours would be the second one on screen. Only older versions need telling.
 */
private fun copyAddress(context: Context, label: String, address: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, address))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(
            context,
            context.getString(R.string.donations_copied, label),
            Toast.LENGTH_SHORT
        ).show()
    }
}

/** The official mark, drawn at full size: these carry their own colour and need no backdrop. */
@Composable
private fun BrandMark(target: DonationTarget) {
    if (target.tinted) {
        Icon(
            painter            = painterResource(target.iconRes),
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface,
            modifier           = Modifier.size(40.dp)
        )
    } else {
        Image(
            painter            = painterResource(target.iconRes),
            contentDescription = null,
            modifier           = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun DonationCard(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isDark   = LocalDarkMode.current
    val isAmoled = LocalAmoledMode.current
    val sv       = MaterialTheme.colorScheme.surfaceVariant
    val cardColor = if (isDark && !isAmoled) sv.copy(alpha = 0.35f) else sv.copy(alpha = 0.5f)

    Surface(
        color    = cardColor,
        shape    = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()

            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) { content() }

            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            } else {
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew, null,
                    modifier = Modifier.size(16.dp),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
