package zip.arcanum.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Google Play's payments policy forbids pointing users at donations outside Play
 * Billing, so the Play build carries no donation screen at all: no links and no
 * wallet addresses in its code. Nothing navigates here on Play; should something
 * ever do so, it goes straight back.
 */
@Composable
internal fun DonationsSubScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
