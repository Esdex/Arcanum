package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.components.AppSheet

/**
 * Bottom sheet showing the "boring" technical vault info — General + Encryption —
 * that is not needed day-to-day. Opened from Vault Config (only while mounted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultDetailsSheet(
    container: Container,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locationDisplay = remember(container) { vaultLocationDisplay(context, container) }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text       = stringResource(R.string.vault_details_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(vertical = 12.dp)
            )
            VaultGeneralSection(container, locationDisplay)
            Spacer(Modifier.height(12.dp))
            VaultEncryptionSection(container)
            Spacer(Modifier.height(12.dp))
            VaultDatesSection(container)
        }
    }
}
